package io.github.hcode91.pocketouya.bridge;
/** Common keyboard fallback, intentionally game-local and visibly selectable. */
public final class KeyboardPadMap {
    private KeyboardPadMap(){}
    public static int key(int key){
        switch(key){case VirtualPad.O:return 62;case VirtualPad.U:return 52;case VirtualPad.Y:return 53;case VirtualPad.A:return 111;
            case VirtualPad.L1:return 45;case VirtualPad.R1:return 33;case VirtualPad.START:return 66;case VirtualPad.SELECT:return 61;default:return key;}
    }
}
