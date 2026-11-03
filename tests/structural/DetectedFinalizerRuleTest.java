package io.github.hozdogus.pocketouya;

import java.util.Arrays;

/** Detector-derived repair on generated layouts: shifted addresses, with and without the Thumb thunk. */
final class DetectedFinalizerRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** Real libraries carry DT_RELCOUNT (0x6ffffffa); add it before the DT_NULL terminator. */
    static byte[] withRelcount(StructuralFixture f, int relative) {
        for (int at = 0x800; at < 0x900; at += 8)
            if (f.b.getInt(at) == 0) { f.b.putInt(at, 0x6ffffffa).putInt(at + 4, relative).putInt(at + 8, 0).putInt(at + 12, 0); return f.bytes; }
        throw new AssertionError("no room for DT_RELCOUNT");
    }

    /** Thumb-2 BL at {@code va} (a halfword-aligned code address == file offset in the fixture) to {@code target}. */
    static void thumbBl(StructuralFixture f, int va, int target) {
        int off = target - (va + 4), sgn = (off >>> 24) & 1, i1 = (off >>> 23) & 1, i2 = (off >>> 22) & 1;
        int j1 = (1 - i1) ^ sgn, j2 = (1 - i2) ^ sgn;
        f.b.putShort(va, (short) (0xf000 | (sgn << 10) | ((off >>> 12) & 0x3ff)));
        f.b.putShort(va + 2, (short) (0xd000 | (j1 << 13) | (j2 << 11) | ((off >>> 1) & 0x7ff)));
    }
    /** ARM BLX (immediate) at {@code va} to Thumb {@code target}. */
    static void armBlx(StructuralFixture f, int va, int target) {
        int off = target - (va + 8);
        f.b.putInt(va, 0xfa000000 | (((off >>> 1) & 1) << 24) | ((off >> 2) & 0xffffff));
    }
    /** ARM BL at {@code va} to {@code target} (claims ARM mode). */
    static void armBl(StructuralFixture f, int va, int target) {
        int off = target - (va + 8);
        f.b.putInt(va, 0xeb000000 | ((off >> 2) & 0xffffff));
    }

    public static void main(String[] args) throws Exception {
        int verified = 0;
        for (int shift : new int[]{0, 28, 84, 128}) for (String mode : new String[]{"none", "typed", "mapped"}) {
            StructuralFixture f = new StructuralFixture(shift, !mode.equals("none"), mode);
            byte[] lib = withRelcount(f, 2), copy = lib.clone();
            var scan = DetectedFinalizerRule.inspect(lib);
            check(scan.status == CompatibilityEvidence.Status.SUPPORTED && scan.replacement() == null, "read-only recognition " + shift + mode + ": " + scan.reason);
            var r = DetectedFinalizerRule.repair(lib);
            check(r.status == CompatibilityEvidence.Status.VERIFIED, "repair " + shift + mode + ": " + r.reason);
            check(Arrays.equals(lib, copy), "input unchanged");
            byte[] out = r.replacement();
            check(!NativeStartupChecks.hasTextRelocations(out), "text relocations no longer declared");
            check(out.length == lib.length, "size unchanged");
            // The finalizer now computes its target PC-relatively: LDR r0,[pc,#4]; ADD r0,pc,r0.
            check(f.b.getInt(f.f) == 0xe28f0004 && java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(f.f) == 0xe59f0004, "finalizer rewritten");
            check(DetectedFinalizerRule.repair(out).status == CompatibilityEvidence.Status.SKIPPED, "idempotent");
            check(Arrays.equals(out, ArmRuntimePicRepair.repairLayout(lib, f.f, f.target, mode.equals("none") ? -1 : f.t, f.plt + 12)), "same bytes as the catalogue repair with known addresses");
            verified++;
        }
        // Thumb mode proven by a call site: stripped symbols, but a Thumb BL (or ARM BLX) targets the thunk.
        for (int shift : new int[]{0, 84}) for (boolean armBlx : new boolean[]{false, true}) {
            StructuralFixture f = new StructuralFixture(shift, true, "stripped");
            if (armBlx) armBlx(f, 0x600, f.t); else thumbBl(f, 0x602, f.t);
            byte[] lib = withRelcount(f, 2);
            var ev = ArmFinalizerDetector.inspect(lib);
            check(ev.status == CompatibilityEvidence.Status.SUPPORTED && ev.findings.stream().anyMatch(x -> x.code.equals("THUMB_CALLERS")), "call-site Thumb proof " + shift + (armBlx ? " blx" : " bl"));
            var fixed = DetectedFinalizerRule.repair(lib);
            check(fixed.status == CompatibilityEvidence.Status.VERIFIED, "repair with call-site proof: " + fixed.reason);
        }
        // An ARM-mode call (ARM BL) to the thunk contradicts Thumb: left alone.
        {
            StructuralFixture f = new StructuralFixture(0, true, "stripped");
            thumbBl(f, 0x602, f.t); armBl(f, 0x640, f.t);
            var ev = ArmFinalizerDetector.inspect(withRelcount(f, 2));
            check(ev.status == CompatibilityEvidence.Status.UNKNOWN && ev.findings.stream().anyMatch(x -> x.code.equals("THUMB_MODE_CONFLICT")), "ARM-mode caller rejected");
        }
        // Thumb mode not proven: detector says UNKNOWN, rule leaves the library alone.
        byte[] stripped = withRelcount(new StructuralFixture(0, true, "stripped"), 2);
        var r = DetectedFinalizerRule.repair(stripped);
        check(r.status == CompatibilityEvidence.Status.UNSUPPORTED && r.replacement() == null && r.reason.contains("THUMB_MODE_UNPROVEN"), "unproven Thumb left alone: " + r.reason);
        // Detector matches but a required field is missing: repair fails closed.
        r = DetectedFinalizerRule.repair(new StructuralFixture(0, false, "none").bytes);
        check(r.status == CompatibilityEvidence.Status.UNKNOWN && r.replacement() == null, "missing RELCOUNT fails closed");
        // Not ARM / no text relocations: skipped.
        check(DetectedFinalizerRule.repair(new byte[64]).status == CompatibilityEvidence.Status.SKIPPED, "non-ELF skipped");
        System.out.println("Detected finalizer repair: " + verified + " shifted layouts (no thunk / typed / mapped Thumb) repaired identically to the catalogue routine; unproven Thumb, missing metadata and non-ELF inputs left unchanged");
    }
}
