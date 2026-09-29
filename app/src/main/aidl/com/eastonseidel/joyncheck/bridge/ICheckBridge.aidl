package com.eastonseidel.joyncheck.bridge;

/** Every probe returns a small JSON object; see joyncheck_native.cpp for the exact fields. */
interface ICheckBridge {
    /** How many /dev/input/eventX nodes shell can open, and how many were denied. */
    String probeEvdev() = 1;

    /** Creates the test /dev/uinput gamepad (same shape as JoynCon's). Returns "" or an error. */
    String createTestDevice() = 2;
    void destroyTestDevice() = 3;

    /** Finds the Joy-Con for "left"/"right", then EVIOCGRAB's and immediately releases it. */
    String probeJoyCon(String role) = 4;

    /** Grabs both Joy-Cons and forwards their input to the test gamepad until stopLiveTest(). */
    String startLiveTest() = 5;
    String pollLiveTest() = 6;
    void stopLiveTest() = 7;

    /** Whether JoynCon's own bridge process is running (it would already hold the Joy-Con grabs). */
    boolean isJoynConBridgeRunning() = 8;

    /** Reserved transaction code Shizuku calls when the service is unbound. */
    void destroy() = 16777114;
}
