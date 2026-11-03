package io.github.hozdogus.pocketouya;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Writes a minimal, valid ELF32 little-endian ARM shared library that exports the given symbols.
 * Used to stand in for private Android system libraries (libstagefright, libui, libutils, ...)
 * whose old C++ symbols were removed in later Android versions; the game's own library can then
 * load. Every function symbol points at a single ARM "bx lr" instruction: it returns its first
 * argument unchanged, so constructors return `this` and status checks (OMXClient::connect,
 * GraphicBuffer::initCheck, ...) report a non-zero error and the engine takes its fallback path.
 * Data symbols are zero-filled blocks whose first word points at an empty C string.
 *
 * Layout: [ELF header | 3 program headers | .hash | .dynsym | .dynstr | .text | .rel.dyn]
 * (read+exec load) then on the next page [.dynamic | .data] (read+write load), followed by
 * section headers (the Android linker requires them). No text relocations.
 */
final class StubLibraryWriter {
    static final int OBJECT_SIZE = 64;
    private static final int PAGE = 0x1000;
    private static final int STT_OBJECT = 1, STT_FUNC = 2, STB_GLOBAL = 1;

    private StubLibraryWriter() {}

    /** @param functions exported function names; @param objects exported data object names -> size (0 = default) */
    static byte[] write(String soname, Collection<String> functions, Map<String, Integer> objects) {
        List<String> funcs = new ArrayList<>(new TreeSet<>(functions));
        List<String> objs = new ArrayList<>(new TreeSet<>(objects.keySet()));
        funcs.removeAll(objs);
        int nsyms = 1 + funcs.size() + objs.size();

        // .dynstr
        ByteArrayOutputStream str = new ByteArrayOutputStream(); str.write(0);
        Map<String, Integer> nameOff = new HashMap<>();
        for (String s : concat(List.of(soname), funcs, objs)) { nameOff.put(s, str.size()); byte[] b = s.getBytes(StandardCharsets.US_ASCII); str.write(b, 0, b.length); str.write(0); }
        byte[] dynstr = str.toByteArray();

        // read+exec segment layout
        int phOff = 52, phNum = 3;
        int hashOff = align(phOff + 32 * phNum, 4);
        int nbucket = Math.max(1, nsyms / 2 + 1);
        int hashSize = 4 * (2 + nbucket + nsyms);
        int symOff = align(hashOff + hashSize, 4);
        int strOff = symOff + 16 * nsyms;
        int textOff = align(strOff + dynstr.length, 4);
        int textSize = 4;                       // bx lr
        int emptyStrOff = textOff + textSize;   // "" for data pointers
        int relOff = align(emptyStrOff + 1, 4);
        int relSize = 8 * objs.size();
        int rxEnd = relOff + relSize;

        // read+write segment on the next page (file offset == vaddr)
        int rwOff = align(rxEnd, PAGE);
        String[] dynTags = {"HASH", "STRTAB", "SYMTAB", "STRSZ", "SYMENT", "SONAME", "REL", "RELSZ", "RELENT", "NULL"};
        int dynOff = rwOff, dynSize = 8 * dynTags.length;
        int dataOff = align(dynOff + dynSize, 8);
        Map<String, Integer> objSize = new HashMap<>(), objAddr = new HashMap<>();
        int cursor = dataOff;
        for (String o : objs) { int sz = Math.max(OBJECT_SIZE, objects.getOrDefault(o, 0)); objSize.put(o, sz); objAddr.put(o, cursor); cursor = align(cursor + sz, 8); }
        int dataSize = Math.max(8, cursor - dataOff);
        int rwEnd = dataOff + dataSize;

        // section names / headers
        String[] shNames = {"", ".hash", ".dynsym", ".dynstr", ".text", ".rel.dyn", ".dynamic", ".data", ".shstrtab"};
        ByteArrayOutputStream shs = new ByteArrayOutputStream(); int[] shName = new int[shNames.length];
        for (int i = 0; i < shNames.length; i++) { shName[i] = shs.size(); byte[] b = shNames[i].getBytes(StandardCharsets.US_ASCII); shs.write(b, 0, b.length); shs.write(0); }
        byte[] shstr = shs.toByteArray();
        int shstrOff = rwEnd;
        int shOff = align(shstrOff + shstr.length, 4);
        int total = shOff + 40 * shNames.length;

        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        // ELF header
        b.put(new byte[]{0x7f, 'E', 'L', 'F', 1, 1, 1, 0});
        b.putShort(16, (short) 3).putShort(18, (short) 40).putInt(20, 1).putInt(24, 0).putInt(28, phOff).putInt(32, shOff)
                .putInt(36, 0x05000000).putShort(40, (short) 52).putShort(42, (short) 32).putShort(44, (short) phNum)
                .putShort(46, (short) 40).putShort(48, (short) shNames.length).putShort(50, (short) (shNames.length - 1));
        // program headers
        ph(b, phOff, 1, 0, 0, rxEnd, rxEnd, 5, PAGE);                         // PT_LOAD r-x
        ph(b, phOff + 32, 1, rwOff, rwOff, rwEnd - rwOff, rwEnd - rwOff, 6, PAGE); // PT_LOAD rw-
        ph(b, phOff + 64, 2, dynOff, dynOff, dynSize, dynSize, 6, 4);       // PT_DYNAMIC

        // symbols (index 0 is the null symbol)
        List<String> order = new ArrayList<>(); order.add(null); order.addAll(funcs); order.addAll(objs);
        for (int i = 1; i < order.size(); i++) {
            String s = order.get(i); boolean obj = objAddr.containsKey(s); int at = symOff + 16 * i;
            b.putInt(at, nameOff.get(s)).putInt(at + 4, obj ? objAddr.get(s) : textOff).putInt(at + 8, obj ? objSize.get(s) : textSize)
                    .put(at + 12, (byte) ((STB_GLOBAL << 4) | (obj ? STT_OBJECT : STT_FUNC))).put(at + 13, (byte) 0).putShort(at + 14, (short) (obj ? 7 : 4));
        }
        // SysV hash table
        b.putInt(hashOff, nbucket).putInt(hashOff + 4, nsyms);
        int[] bucket = new int[nbucket], chain = new int[nsyms];
        for (int i = 1; i < nsyms; i++) { int h = (int) (elfHash(order.get(i)) % nbucket); chain[i] = bucket[h]; bucket[h] = i; }
        for (int i = 0; i < nbucket; i++) b.putInt(hashOff + 8 + 4 * i, bucket[i]);
        for (int i = 0; i < nsyms; i++) b.putInt(hashOff + 8 + 4 * nbucket + 4 * i, chain[i]);
        // strings, code, empty string
        b.position(strOff); b.put(dynstr);
        b.putInt(textOff, 0xe12fff1e);  // bx lr
        b.put(emptyStrOff, (byte) 0);
        // relocations: each data object's first word -> "" (R_ARM_RELATIVE, addend in place)
        for (int i = 0; i < objs.size(); i++) {
            int addr = objAddr.get(objs.get(i));
            b.putInt(relOff + 8 * i, addr).putInt(relOff + 8 * i + 4, 23);
            b.putInt(addr, emptyStrOff);
        }
        // dynamic table
        int[][] dyn = {{4, hashOff}, {5, strOff}, {6, symOff}, {10, dynstr.length}, {11, 16}, {14, nameOff.get(soname)}, {17, relOff}, {18, relSize}, {19, 8}, {0, 0}};
        for (int i = 0; i < dyn.length; i++) b.putInt(dynOff + 8 * i, dyn[i][0]).putInt(dynOff + 8 * i + 4, dyn[i][1]);
        // section header string table + section headers
        b.position(shstrOff); b.put(shstr);
        sh(b, shOff, 1, shName[1], 5, 2, hashOff, hashOff, hashSize, 2, 0, 4, 4);          // .hash  -> .dynsym
        sh(b, shOff, 2, shName[2], 11, 2, symOff, symOff, 16 * nsyms, 3, 1, 4, 16);       // .dynsym -> .dynstr
        sh(b, shOff, 3, shName[3], 3, 2, strOff, strOff, dynstr.length, 0, 0, 1, 0);      // .dynstr
        sh(b, shOff, 4, shName[4], 1, 6, textOff, textOff, textSize, 0, 0, 4, 0);         // .text
        sh(b, shOff, 5, shName[5], 9, 2, relOff, relOff, relSize, 2, 0, 4, 8);            // .rel.dyn
        sh(b, shOff, 6, shName[6], 6, 3, dynOff, dynOff, dynSize, 3, 0, 4, 8);            // .dynamic -> .dynstr
        sh(b, shOff, 7, shName[7], 1, 3, dataOff, dataOff, dataSize, 0, 0, 8, 0);         // .data
        sh(b, shOff, 8, shName[8], 3, 0, 0, shstrOff, shstr.length, 0, 0, 1, 0);          // .shstrtab
        return b.array();
    }

    @SafeVarargs private static List<String> concat(List<String>... lists) { List<String> out = new ArrayList<>(); for (List<String> l : lists) out.addAll(l); return out; }
    private static int align(int v, int a) { return (v + a - 1) / a * a; }
    private static void ph(ByteBuffer b, int at, int type, int off, int va, int file, int mem, int flags, int alignment) {
        b.putInt(at, type).putInt(at + 4, off).putInt(at + 8, va).putInt(at + 12, va).putInt(at + 16, file).putInt(at + 20, mem).putInt(at + 24, flags).putInt(at + 28, alignment);
    }
    private static void sh(ByteBuffer b, int shOff, int index, int name, int type, int flags, int addr, int off, int size, int link, int info, int alignment, int entsize) {
        int at = shOff + 40 * index;
        b.putInt(at, name).putInt(at + 4, type).putInt(at + 8, flags).putInt(at + 12, addr).putInt(at + 16, off).putInt(at + 20, size)
                .putInt(at + 24, link).putInt(at + 28, info).putInt(at + 32, alignment).putInt(at + 36, entsize);
    }
    static long elfHash(String name) {
        long h = 0;
        for (byte c : name.getBytes(StandardCharsets.US_ASCII)) { h = (h << 4) + (c & 0xff); long g = h & 0xf0000000L; if (g != 0) h ^= g >>> 24; h &= ~g; }
        return h & 0xffffffffL;
    }
}
