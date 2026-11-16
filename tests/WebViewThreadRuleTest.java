package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Old AdMob-style background WebView loads are sent to the main thread; other calls stay. */
public final class WebViewThreadRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String WV = "Landroid/webkit/WebView;", AD = "Lg;", TASK = "Lc;", S = "Ljava/lang/String;";

    static Method body(String owner, String name, List<String> params, int regs, Instruction... code) {
        List<ImmutableMethodParameter> p = new ArrayList<>(); for (String t : params) p.add(new ImmutableMethodParameter(t, Set.of(), null));
        return new ImmutableMethod(owner, name, p, "V", 1, Set.of(), Set.of(), new ImmutableMethodImplementation(regs, List.of(code), List.of(), List.of()));
    }
    static byte[] dex(ClassDef... classes) throws Exception {
        DexPool pool = new DexPool(Opcodes.getDefault()); for (ClassDef c : classes) pool.internClass(c);
        MemoryDataStore out = new MemoryDataStore(); pool.writeTo(out); return out.getData();
    }
    static ClassDef cls(String type, String sup, Method... m) { return new ImmutableClassDef(type, 1, sup, List.of(), null, Set.of(), List.of(), List.of(m)); }
    static List<MethodReference> calls(byte[] dex, String owner, String method) {
        List<MethodReference> out = new ArrayList<>();
        for (ClassDef c : new DexBackedDexFile(Opcodes.getDefault(), dex).getClasses()) if (c.getType().equals(owner))
            for (Method m : c.getMethods()) if (m.getName().equals(method))
                for (Instruction i : m.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof MethodReference) out.add((MethodReference) ((ReferenceInstruction) i).getReference());
        return out;
    }

    public static void main(String[] args) throws Exception {
        // c.a(): AdMob 4.x AsyncTask loads its page into g (a WebView subclass) with 6 registers (range) and loadUrl (35c).
        Method load = body(TASK, "a", List.of(), 7,
                new ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 6, new ImmutableMethodReference(AD, "loadDataWithBaseURL", List.of(S, S, S, S, S), "V")),
                new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, new ImmutableMethodReference(WV, "loadUrl", List.of(S), "V")),
                new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, new ImmutableMethodReference(WV, "getUrl", List.of(), S)),
                new ImmutableInstruction10x(Opcode.RETURN_VOID));
        Method other = body(TASK, "b", List.of(), 2,
                new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, new ImmutableMethodReference("Lcom/example/Browser;", "loadUrl", List.of(S), "V")),
                new ImmutableInstruction10x(Opcode.RETURN_VOID));
        byte[] input = dex(cls(AD, WV), cls(TASK, "Landroid/os/AsyncTask;", load, other), cls("Lcom/example/Browser;", "Ljava/lang/Object;"));

        var scan = WebViewThreadRule.inspect(Map.of("classes.dex", input));
        check(scan.calls == 2 && scan.status == CompatibilityEvidence.Status.SUPPORTED && scan.replacements.isEmpty(), "two WebView loads found (subclass and WebView), not getUrl or a non-WebView loadUrl");
        var fixed = WebViewThreadRule.repair(Map.of("classes.dex", input));
        check(fixed.status == CompatibilityEvidence.Status.VERIFIED, "repaired: " + fixed.reason);
        byte[] out = fixed.replacements.get("classes.dex");
        List<MethodReference> after = calls(out, TASK, "a");
        check(after.get(0).getDefiningClass().equals(WebViewThreadRule.HELPER) && after.get(0).getName().equals("loadDataWithBaseURL") && after.get(0).getParameterTypes().size() == 6, "range load goes to the helper");
        check(after.get(1).getDefiningClass().equals(WebViewThreadRule.HELPER) && after.get(1).getName().equals("loadUrl"), "loadUrl goes to the helper");
        check(after.get(2).getName().equals("getUrl") && after.get(2).getDefiningClass().equals(WV), "other WebView calls unchanged");
        check(calls(out, TASK, "b").get(0).getDefiningClass().equals("Lcom/example/Browser;"), "non-WebView loadUrl unchanged");
        ClassDef helper = null; for (ClassDef c : new DexBackedDexFile(Opcodes.getDefault(), out).getClasses()) if (c.getType().equals(WebViewThreadRule.HELPER)) helper = c;
        check(helper != null && helper.getInterfaces().contains("Ljava/lang/Runnable;"), "helper class added as a Runnable");
        Set<String> names = new HashSet<>(); for (Method m : helper.getMethods()) names.add(m.getName());
        check(names.containsAll(Set.of("<init>", "run", "go", "loadUrl", "loadData", "loadDataWithBaseURL", "stopLoading")), "helper methods: " + names);
        List<MethodReference> go = calls(out, WebViewThreadRule.HELPER, "go");
        check(go.stream().anyMatch(r -> r.getName().equals("myLooper")) && go.stream().anyMatch(r -> r.getDefiningClass().equals("Landroid/os/Handler;") && r.getName().equals("post")), "posts to the main looper");
        check(WebViewThreadRule.repair(Map.of("classes.dex", out)).replacements.isEmpty(), "idempotent");
        check(WebViewThreadRule.repair(Map.of("classes.dex", dex(cls("Lcom/example/Plain;", "Ljava/lang/Object;")))).status == CompatibilityEvidence.Status.SKIPPED, "no WebView, skipped");
        check(WebViewThreadRule.repair(Map.of("classes.dex", new byte[]{1, 2})).status == CompatibilityEvidence.Status.UNKNOWN, "malformed retained");
        System.out.println("WebView main-thread rule: subclass/range/35c loads routed to a posting helper, other calls untouched, idempotent, malformed retained");
    }
}
