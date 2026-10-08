namespace PersonalAiWorkspace.Core;

public enum ModelAction { SWITCH, RELEASE_OLD_THEN_SWITCH, RELEASE, VALIDATE, RECOVER }
public enum ModelFact { TRUE, FALSE, UNKNOWN }
public sealed record ModelCatalogEntry(string Handle, string Model, string Digest, long ContextLimit,
    bool Completion, bool LocalSourceVerified, bool ProviderDeclaredVision);
public sealed record ModelCatalog(long SelectionRevision, ModelCatalogEntry[] Models);
public sealed record ModelStatus(string? ConfiguredModel, string? ConfiguredDigest, string? ActiveModel, string? ActiveDigest,
    long SelectionRevision, ModelFact Installed, ModelFact Loaded, bool Ready, int Reserved, int Queued, int Executing,
    int Draining, bool Switching, bool Uncertain, string RecoveryGeneration, bool ValidationRequired, string? Error);
// Native-only immutable intent. React can request a dialog but cannot create confirmation authority.
public sealed record ModelIntent(ModelAction Action, string CatalogHandle, string CandidateModel, string CandidateDigest,
    long ExpectedSelectionRevision, string? ExpectedActiveModel, string? ExpectedActiveDigest,
    string? RecoveryGeneration, bool ExternalConfirmed)
{ public override string ToString() => "ModelIntent[private]"; }
