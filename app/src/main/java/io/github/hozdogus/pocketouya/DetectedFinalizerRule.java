package io.github.hozdogus.pocketouya;

import java.io.IOException;

/**
 * elf.arm32.detected-finalizer: the general version of the catalogued Unity/Marmalade/Frima
 * text-relocation repair. Instead of matching one library build by SHA-256, the read-only
 * ArmFinalizerDetector locates the ARM finalizer, its data target, the Thumb __cxa_atexit thunk and
 * the PLT entry in any library. Only when the detector reports SUPPORTED with exactly one candidate
 * are those addresses passed to the same (unchanged) ArmRuntimePicRepair.repairLayout used by the
 * catalogue, which re-checks every instruction byte before writing and fails closed otherwise.
 * For all 12 catalogued libraries of this family the detector derives exactly the catalogue's
 * addresses (docs/evidence/structural-prototype/native-profile-results.json).
 */
public final class DetectedFinalizerRule {
    public static final String ID = "elf.arm32.detected-finalizer";

    public static final class Result {
        public CompatibilityEvidence.Status status = CompatibilityEvidence.Status.SKIPPED;
        public String reason = "No declared text relocations";
        byte[] replacement;
        public byte[] replacement() { return replacement == null ? null : replacement.clone(); }
    }

    private DetectedFinalizerRule() {}

    public static Result inspect(byte[] lib) { return run(lib, false); }
    public static Result repair(byte[] lib) { return run(lib, true); }

    private static Result run(byte[] lib, boolean apply) {
        Result r = new Result();
        if (lib == null || !NativeStartupChecks.hasTextRelocations(lib)) return r;
        CompatibilityEvidence evidence = ArmFinalizerDetector.inspect(lib);
        if (evidence.status != CompatibilityEvidence.Status.SUPPORTED) {
            r.status = evidence.status == CompatibilityEvidence.Status.SKIPPED ? CompatibilityEvidence.Status.SKIPPED : CompatibilityEvidence.Status.UNSUPPORTED;
            r.reason = "pattern not recognised: " + blocker(evidence) + "; unchanged";
            return r;
        }
        if (evidence.candidates.size() != 1) {
            r.status = CompatibilityEvidence.Status.UNKNOWN;
            r.reason = evidence.candidates.size() + " finalizer candidates; unchanged";
            return r;
        }
        CompatibilityEvidence.Candidate c = evidence.candidates.get(0);
        String where = String.format(java.util.Locale.ROOT, "finalizer 0x%x, target 0x%x%s", c.finalizer, c.target,
                c.thunk >= 0 ? String.format(java.util.Locale.ROOT, ", atexit thunk 0x%x, PLT 0x%x", c.thunk, c.atexitPlt) : "");
        if (!apply) {
            r.status = CompatibilityEvidence.Status.SUPPORTED;
            r.reason = "recognised ARM finalizer text relocation (" + where + ")";
            return r;
        }
        try {
            if (c.finalizer > Integer.MAX_VALUE || c.target > Integer.MAX_VALUE || c.thunk > Integer.MAX_VALUE || c.atexitPlt > Integer.MAX_VALUE)
                throw new IOException("address out of range");
            byte[] fixed = ArmRuntimePicRepair.repairLayout(lib, (int) c.finalizer, (int) c.target, (int) c.thunk, (int) c.atexitPlt);
            if (NativeStartupChecks.hasTextRelocations(fixed)) throw new IOException("text relocations still declared");
            if (fixed.length != lib.length) throw new IOException("library size changed");
            r.status = CompatibilityEvidence.Status.VERIFIED;
            r.reason = "repaired detected ARM finalizer text relocation (" + where + "); text-relocation declaration removed";
            r.replacement = fixed;
        } catch (IOException | RuntimeException e) {
            r.status = CompatibilityEvidence.Status.UNKNOWN;
            r.reason = "detector matched but repair checks failed (" + e.getMessage() + "); unchanged";
        }
        return r;
    }

    private static String blocker(CompatibilityEvidence e) {
        for (CompatibilityEvidence.Finding f : e.findings)
            if (f.status != CompatibilityEvidence.Status.VERIFIED) return f.code + " — " + f.detail;
        return String.valueOf(e.status);
    }
}
