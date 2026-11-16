package com.hcode91.pocketmicro;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

public final class ClickteamSurfaceLayoutRuleTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static final String SURFACE = ClickteamSurfaceLayoutRule.SURFACE, LP = ClickteamSurfaceLayoutRule.LP, MAIN_LP = ClickteamSurfaceLayoutRule.MAIN_LP, APP = "LApplication/CRunApp;";

    static ClassDef mainLp() {
        List<Field> fields = List.of(new ImmutableField(MAIN_LP, "x", "I", 1, null, Set.of(), Set.of()), new ImmutableField(MAIN_LP, "y", "I", 1, null, Set.of(), Set.of()));
        return new ImmutableClassDef(MAIN_LP, 1, LP, List.of(), null, Set.of(), fields, List.of());
    }

    /** CRunApp.setSurfaceEnabled-like caller: v0 = SurfaceView, v1 = LayoutParams. */
    static ClassDef app() {
        var ref = new ImmutableMethodReference(SURFACE, "setLayoutParams", List.of(LP), "V");
        var body = new ImmutableMethodImplementation(2, List.of(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, ref), new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
        Method m = new ImmutableMethod(APP, "setSurfaceEnabled", List.of(), "V", 1, Set.of(), Set.of(), body);
        return GeneralCompatibilityTest.cls(APP, "Ljava/lang/Object;", List.of(m));
    }

    static Map<String, byte[]> input(boolean clickteam) throws Exception {
        List<ClassDef> c = new ArrayList<>(List.of(app(), mainLp()));
        if (clickteam) {
            c.add(GeneralCompatibilityTest.cls(ClickteamSurfaceLayoutRule.RUNTIME, "Landroid/app/Activity;", List.of()));
            c.add(GeneralCompatibilityTest.cls(SURFACE, "Landroid/view/SurfaceView;", List.of()));
        }
        return new LinkedHashMap<>(Map.of("classes.dex", GeneralCompatibilityTest.dex(c.toArray(new ClassDef[0]))));
    }

    static Method find(byte[] dex, String name) {
        for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
        throw new AssertionError("missing " + name);
    }

    // --- tiny model of the views the helper touches ---
    static final class Lp { int width, height, x, y; boolean main; Lp(int w, int h, int x, int y, boolean main) { width = w; height = h; this.x = x; this.y = y; this.main = main; } }
    static final class View { int width, height; View parent; Lp lp; String measured, laid; View(int w, int h) { width = w; height = h; } }

    /** Interprets the emitted helper against the model. */
    static void run(Method helper, View view, Lp lp) {
        var impl = helper.getImplementation();
        Map<Integer, Instruction> code = new HashMap<>(); Map<Integer, Integer> offsets = new HashMap<>(); int at = 0;
        for (Instruction i : impl.getInstructions()) { code.put(at, i); at += i.getCodeUnits(); }
        Object[] r = new Object[impl.getRegisterCount()]; r[8] = view; r[9] = lp; Object result = null; int pc = 0;
        for (int step = 0; step < 200; step++) {
            Instruction i = code.get(pc); int next = pc + i.getCodeUnits();
            int a = i instanceof OneRegisterInstruction ? ((OneRegisterInstruction) i).getRegisterA() : -1;
            switch (i.getOpcode()) {
                case INVOKE_VIRTUAL: {
                    var f = (FiveRegisterInstruction) i; String n = ((MethodReference) ((ReferenceInstruction) i).getReference()).getName(); View v = (View) r[f.getRegisterC()];
                    switch (n) {
                        case "setLayoutParams": v.lp = (Lp) r[f.getRegisterD()]; break;
                        case "getWidth": result = v.width; break;
                        case "getHeight": result = v.height; break;
                        case "getParent": result = v.parent; break;
                        case "measure": v.measured = (((int) r[f.getRegisterD()]) & 0x3fffffff) + "x" + (((int) r[f.getRegisterE()]) & 0x3fffffff)
                                + ((((int) r[f.getRegisterD()]) & 0xc0000000) == 0x40000000 ? " exact" : " loose"); break;
                        case "layout": v.laid = r[f.getRegisterD()] + "," + r[f.getRegisterE()] + "," + r[f.getRegisterF()] + "," + r[f.getRegisterG()];
                            v.width = (int) r[f.getRegisterF()] - (int) r[f.getRegisterD()]; break;
                        default: throw new AssertionError("unexpected call " + n);
                    }
                    break;
                }
                case MOVE_RESULT: case MOVE_RESULT_OBJECT: r[a] = result; break;
                case CHECK_CAST: break;
                case INSTANCE_OF: {
                    var t = (TwoRegisterInstruction) i; Object o = r[t.getRegisterB()]; String type = ((TypeReference) ((ReferenceInstruction) i).getReference()).getType();
                    r[a] = o != null && (type.equals(MAIN_LP) ? o instanceof Lp && ((Lp) o).main : true) ? 1 : 0; break;
                }
                case IGET: {
                    Lp o = (Lp) r[((TwoRegisterInstruction) i).getRegisterB()]; String n = ((FieldReference) ((ReferenceInstruction) i).getReference()).getName();
                    r[a] = n.equals("width") ? o.width : n.equals("height") ? o.height : n.equals("x") ? o.x : o.y; break;
                }
                case MOVE: r[a] = r[((TwoRegisterInstruction) i).getRegisterB()]; break;
                case CONST_4: case CONST_HIGH16: r[a] = (int) ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                case OR_INT: { var t = (ThreeRegisterInstruction) i; r[a] = (int) r[t.getRegisterB()] | (int) r[t.getRegisterC()]; break; }
                case ADD_INT: { var t = (ThreeRegisterInstruction) i; r[a] = (int) r[t.getRegisterB()] + (int) r[t.getRegisterC()]; break; }
                case IF_EQZ: case IF_GTZ: case IF_LEZ: case IF_GEZ: {
                    Object v = r[a]; int n = v == null ? 0 : v instanceof Integer ? (int) v : 1;
                    boolean jump = i.getOpcode() == Opcode.IF_EQZ ? n == 0 : i.getOpcode() == Opcode.IF_GTZ ? n > 0 : i.getOpcode() == Opcode.IF_LEZ ? n <= 0 : n >= 0;
                    if (jump) next = pc + ((OffsetInstruction) i).getCodeOffset(); break;
                }
                case RETURN_VOID: return;
                default: throw new AssertionError("unexpected " + i.getOpcode());
            }
            pc = next;
        }
        throw new AssertionError("helper did not return");
    }

    public static void main(String[] args) throws Exception {
        check(ClickteamSurfaceLayoutRule.inspect(input(false)).status == CompatibilityEvidence.Status.SKIPPED, "non-Clickteam games are untouched");
        var in = input(true);
        check(ClickteamSurfaceLayoutRule.inspect(in).status == CompatibilityEvidence.Status.SUPPORTED, "Clickteam surface call detected");
        var r = ClickteamSurfaceLayoutRule.repair(in);
        check(r.status == CompatibilityEvidence.Status.VERIFIED, "repair verified: " + r.reason);
        byte[] out = r.replacements.get("classes.dex");
        Method caller = find(out, "setSurfaceEnabled"), helper = find(out, ClickteamSurfaceLayoutRule.HELPER);
        Instruction first = caller.getImplementation().getInstructions().iterator().next();
        check(first.getOpcode() == Opcode.INVOKE_STATIC && ((MethodReference) ((ReferenceInstruction) first).getReference()).getName().equals(ClickteamSurfaceLayoutRule.HELPER), "call site uses helper");
        check(((FiveRegisterInstruction) first).getRegisterC() == 0 && ((FiveRegisterInstruction) first).getRegisterD() == 1, "call site keeps registers");
        check(helper.getAccessFlags() == 0x100a && helper.getDefiningClass().equals(APP), "private static synthetic helper in caller class");
        var again = new LinkedHashMap<>(in); again.putAll(r.replacements);
        check(ClickteamSurfaceLayoutRule.repair(again).status == CompatibilityEvidence.Status.SKIPPED, "repair is idempotent");

        // Created mid-layout: parent sized, surface never placed -> placed like MainView.onLayout (MATCH_PARENT = parent size).
        View parent = new View(2009, 1008), surface = new View(0, 0); surface.parent = parent;
        run(helper, surface, new Lp(-1, -1, 0, 0, true));
        check(surface.lp != null, "original setLayoutParams still made");
        check("2009x1008 exact".equals(surface.measured) && "0,0,2009,1008".equals(surface.laid), "unplaced surface measured and placed: " + surface.measured + " / " + surface.laid);
        // Explicit size and offset are honoured.
        View s2 = new View(0, 0); s2.parent = parent; run(helper, s2, new Lp(800, 600, 10, 20, true));
        check("800x600 exact".equals(s2.measured) && "10,20,810,620".equals(s2.laid), "explicit size/offset: " + s2.laid);
        // Already placed, unsized parent, or no parent: only setLayoutParams.
        View placed = new View(500, 500); placed.parent = parent; run(helper, placed, new Lp(-1, -1, 0, 0, true));
        check(placed.measured == null && placed.lp != null, "placed surface left to Android layout");
        View s3 = new View(0, 0); s3.parent = new View(0, 0); run(helper, s3, new Lp(-1, -1, 0, 0, true));
        check(s3.measured == null, "unsized parent: nothing copied");
        View s4 = new View(0, 0); run(helper, s4, new Lp(-1, -1, 0, 0, true));
        check(s4.measured == null && s4.lp != null, "no parent: only setLayoutParams");
        System.out.println("ClickteamSurfaceLayoutRuleTest: PASS");
    }
}
