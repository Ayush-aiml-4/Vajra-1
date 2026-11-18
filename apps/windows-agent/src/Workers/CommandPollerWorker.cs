using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Vajra.WindowsAgent.Commands;
using Vajra.WindowsAgent.Configuration;
using Vajra.WindowsAgent.Identity;
using Vajra.WindowsAgent.Networking;

namespace Vajra.WindowsAgent.Workers;

public sealed class CommandPollerWorker : BackgroundService
{
    private sealed class PersistedCommandRecord
    {
        public string State { get; set; } = "SUBMITTED";
        public string Status { get; set; } = "FAILED";
        public string SafeMessage { get; set; } = string.Empty;
        public string? ResultMetadataJson { get; set; }
    }

    private readonly AgentOptions _options;
    private readonly DeviceIdentityStore _store;
    private readonly VajraApiClient _api;
    private readonly ILogger<CommandPollerWorker> _logger;
    private readonly object _ledgerLock = new();
    private Dictionary<string, PersistedCommandRecord>? _ledgerCache;

    public CommandPollerWorker(
        AgentOptions options,
        DeviceIdentityStore store,
        VajraApiClient api,
        ILogger<CommandPollerWorker> logger)
    {
        _options = options;
        _store = store;
        _api = api;
        _logger = logger;
    }

    private string LedgerFilePath => Path.Combine(_options.ResolveDataDirectory(), "command-ledger.json");

    private Dictionary<string, PersistedCommandRecord> LoadLedgerUnlocked()
    {
        if (_ledgerCache is not null) return _ledgerCache;
        try
        {
            if (File.Exists(LedgerFilePath))
            {
                var json = File.ReadAllText(LedgerFilePath);
                var loaded = JsonSerializer.Deserialize<Dictionary<string, PersistedCommandRecord>>(json);
                if (loaded is not null)
                {
                    _ledgerCache = loaded;
                    return _ledgerCache;
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not load command ledger; initializing clean ledger.");
        }
        _ledgerCache = new Dictionary<string, PersistedCommandRecord>(StringComparer.Ordinal);
        return _ledgerCache;
    }

    private PersistedCommandRecord? GetLedgerEntry(string commandId)
    {
        lock (_ledgerLock)
        {
            var ledger = LoadLedgerUnlocked();
            return ledger.TryGetValue(commandId, out var rec) ? rec : null;
        }
    }

    private void SaveLedgerEntry(string commandId, PersistedCommandRecord record)
    {
        lock (_ledgerLock)
        {
            var ledger = LoadLedgerUnlocked();
            ledger[commandId] = record;
            try
            {
                var dir = Path.GetDirectoryName(LedgerFilePath);
                if (!string.IsNullOrWhiteSpace(dir))
                    Directory.CreateDirectory(dir);
                File.WriteAllText(LedgerFilePath, JsonSerializer.Serialize(ledger));
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Failed to persist command ledger state for {CommandId}.", commandId);
            }
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var interval = TimeSpan.FromSeconds(Math.Clamp(_options.CommandPollIntervalSeconds, 10, 300));
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                if (!_store.HasIdentity)
                {
                    await Task.Delay(interval, stoppingToken).ConfigureAwait(false);
                    continue;
                }

                var identity = _store.LoadIdentity();
                if (identity is null ||
                    identity.LifecycleState is not ("TRUSTED" or "ACTIVE" or "OFFLINE"))
                {
                    await Task.Delay(interval, stoppingToken).ConfigureAwait(false);
                    continue;
                }

                using var pendingRes = await _api.GetPendingCommandsAsync(stoppingToken).ConfigureAwait(false);
                if ((int)pendingRes.StatusCode is 401 or 403)
                {
                    var errBody = await pendingRes.Content.ReadAsStringAsync(stoppingToken).ConfigureAwait(false);
                    if (errBody.Contains("REVOKED", StringComparison.OrdinalIgnoreCase) ||
                        errBody.Contains("DECOMMISSIONED", StringComparison.OrdinalIgnoreCase) ||
                        errBody.Contains("DEVICE_CREDENTIAL_INACTIVE", StringComparison.OrdinalIgnoreCase))
                    {
                        identity.LifecycleState = "REVOKED";
                        _store.SaveIdentity(identity);
                        _logger.LogError("Command polling halted: device revoked or decommissioned.");
                    }
                    await Task.Delay(interval, stoppingToken).ConfigureAwait(false);
                    continue;
                }

                if (!pendingRes.IsSuccessStatusCode)
                {
                    _logger.LogWarning("Pending commands poll failed: {Status}", (int)pendingRes.StatusCode);
                    await Task.Delay(interval, stoppingToken).ConfigureAwait(false);
                    continue;
                }

                await using var stream = await pendingRes.Content.ReadAsStreamAsync(stoppingToken).ConfigureAwait(false);
                using var doc = await JsonDocument.ParseAsync(stream, cancellationToken: stoppingToken).ConfigureAwait(false);
                if (!doc.RootElement.TryGetProperty("commands", out var commands) &&
                    !doc.RootElement.TryGetProperty("pendingCommands", out commands))
                {
                    if (doc.RootElement.ValueKind == JsonValueKind.Array)
                        commands = doc.RootElement;
                    else if (doc.RootElement.TryGetProperty("pendingCommands", out var pc))
                        commands = pc;
                }

                if (commands.ValueKind == JsonValueKind.Array)
                {
                    foreach (var cmd in commands.EnumerateArray())
                    {
                        await ProcessCommandAsync(cmd, stoppingToken).ConfigureAwait(false);
                    }
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Command poll error.");
            }

            await Task.Delay(interval, stoppingToken).ConfigureAwait(false);
        }
    }

    private async Task ProcessCommandAsync(JsonElement cmd, CancellationToken ct)
    {
        var commandId = cmd.TryGetProperty("id", out var idEl) ? idEl.GetString() : null;
        var actionType = cmd.TryGetProperty("actionType", out var atEl) ? atEl.GetString() : null;
        if (string.IsNullOrWhiteSpace(commandId) || string.IsNullOrWhiteSpace(actionType))
            return;

        var existing = GetLedgerEntry(commandId);
        if (existing is not null && existing.State == "SUBMITTED")
        {
            _logger.LogDebug("Skipping already submitted command {CommandId}", commandId);
            return;
        }

        if (existing is not null && existing.State == "PENDING_RESULT_SUBMIT")
        {
            object? savedMetadata = null;
            if (!string.IsNullOrWhiteSpace(existing.ResultMetadataJson))
            {
                savedMetadata = JsonSerializer.Deserialize<JsonElement>(existing.ResultMetadataJson);
            }
            await TrySubmitResultAsync(commandId, existing.Status, existing.SafeMessage, savedMetadata, existing.ResultMetadataJson, ct)
                .ConfigureAwait(false);
            return;
        }

        var isExpired = false;
        if (cmd.TryGetProperty("expiresAt", out var expEl) &&
            expEl.ValueKind == JsonValueKind.String &&
            DateTimeOffset.TryParse(expEl.GetString(), out var expiresAt) &&
            expiresAt <= DateTimeOffset.UtcNow)
        {
            isExpired = true;
        }

        // Acknowledge first
        try
        {
            using var ack = await _api.AcknowledgeCommandAsync(commandId, ct).ConfigureAwait(false);
            if ((int)ack.StatusCode is 404 or 410)
            {
                SaveLedgerEntry(commandId, new PersistedCommandRecord { State = "SUBMITTED", Status = "FAILED", SafeMessage = "Command no longer active on server." });
                return;
            }
            if (!ack.IsSuccessStatusCode && (int)ack.StatusCode != 409)
                _logger.LogWarning("Ack failed for {CommandId}: {Status}", commandId, (int)ack.StatusCode);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Ack error for {CommandId}", commandId);
        }

        string status;
        string safeMessage;
        object? metadata = null;

        if (isExpired)
        {
            status = "FAILED";
            safeMessage = "Command expired prior to device execution.";
        }
        else if (!CommandCapabilityMatrix.IsKnown(actionType))
        {
            status = "FAILED";
            safeMessage = "Unknown action type rejected by Windows agent.";
        }
        else if (!CommandCapabilityMatrix.IsExecutableOnWindows(actionType))
        {
            status = "FAILED";
            safeMessage = CommandCapabilityMatrix.UnsupportedReason(actionType);
        }
        else if (actionType == CommandCapabilityMatrix.RequestDeviceStatus)
        {
            status = "COMPLETED";
            safeMessage = "Windows agent status report completed.";
            metadata = new
            {
                agentVersion = _options.AgentVersion,
                osDescription = RuntimeInformation.OSDescription,
                framework = RuntimeInformation.FrameworkDescription,
                architecture = RuntimeInformation.OSArchitecture.ToString(),
                machineName = Environment.MachineName,
            };
        }
        else
        {
            status = "FAILED";
            safeMessage = "No local handler.";
        }

        var metadataJson = metadata is null ? null : JsonSerializer.Serialize(metadata);
        SaveLedgerEntry(commandId, new PersistedCommandRecord
        {
            State = "PENDING_RESULT_SUBMIT",
            Status = status,
            SafeMessage = safeMessage,
            ResultMetadataJson = metadataJson,
        });

        await TrySubmitResultAsync(commandId, status, safeMessage, metadata, metadataJson, ct).ConfigureAwait(false);
    }

    private async Task TrySubmitResultAsync(
        string commandId,
        string status,
        string safeMessage,
        object? metadata,
        string? metadataJson,
        CancellationToken ct)
    {
        var resultBody = new
        {
            status,
            safeMessage,
            resultMetadata = metadata,
        };

        try
        {
            using var resultRes = await _api.SubmitCommandResultAsync(commandId, resultBody, ct).ConfigureAwait(false);
            if (resultRes.IsSuccessStatusCode || (int)resultRes.StatusCode is 409 or 404 or 410)
            {
                SaveLedgerEntry(commandId, new PersistedCommandRecord
                {
                    State = "SUBMITTED",
                    Status = status,
                    SafeMessage = safeMessage,
                    ResultMetadataJson = metadataJson,
                });
                _logger.LogInformation("Result submitted for {CommandId}: {Status}", commandId, status);
            }
            else
            {
                _logger.LogWarning("Result submit failed for {CommandId}: {Code}", commandId, (int)resultRes.StatusCode);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Result submit error for {CommandId}", commandId);
        }
    }
}
