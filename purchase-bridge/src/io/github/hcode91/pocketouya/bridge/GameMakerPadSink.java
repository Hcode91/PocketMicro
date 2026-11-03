package io.github.hcode91.pocketouya.bridge;

/**
 * Touch pad route for GameMaker 1.x games: buttons and sticks go into {@link GameMakerPad}, which
 * the converted runner reports as a connected controller. Keys are also forwarded to an optional
 * key sink so menus that read the keyboard (arrows/Enter) keep working.
 */
public final class GameMakerPadSink implements VirtualPad.Sink {
    static final String RUNNER = "com.yoyogames.runner.RunnerJNILib";
    private final Class<?> runner;
    private final VirtualPad.Sink keys;

    public GameMakerPadSink(ClassLoader loader, VirtualPad.Sink keys) throws ReflectiveOperationException {
        runner = Class.forName(RUNNER, false, loader);
        if (!GameMakerPad.supported(runner)) throw new ClassNotFoundException("GameMaker runner was not converted with controller support");
        this.keys = keys;
    }

    static int buttonIndex(int key) {
        switch (key) {
            case VirtualPad.O: return 0;      // gp_face1 (bottom)
            case VirtualPad.A: return 1;      // gp_face2 (right)
            case VirtualPad.U: return 2;      // gp_face3 (left)
            case VirtualPad.Y: return 3;      // gp_face4 (top)
            case VirtualPad.L1: return 4;     // gp_shoulderl
            case VirtualPad.R1: return 5;     // gp_shoulderr
            case VirtualPad.SELECT: return 8; // gp_select
            case VirtualPad.START: case VirtualPad.MENU: return 9; // gp_start
            case VirtualPad.UP: return 12; case VirtualPad.DOWN: return 13; case VirtualPad.LEFT: return 14; case VirtualPad.RIGHT: return 15;
            default: return -1;
        }
    }
    static int axisIndex(int androidAxis) {
        switch (androidAxis) { case 0: return 0; case 1: return 1; case 11: return 2; case 14: return 3; default: return -1; }
    }

    private void ensureActive() {
        try { GameMakerPad.activate(runner); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("GameMaker controller registration failed", e); }
    }

    @Override public void key(int key, int action) {
        ensureActive();
        GameMakerPad.button(buttonIndex(key), action == 0);
        if (keys != null) keys.key(key, action);
    }

    @Override public void axis(int axis, float value) {
        ensureActive();
        GameMakerPad.axis(axisIndex(axis), value);
    }
}
