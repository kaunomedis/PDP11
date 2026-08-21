//Fourth isolated reproduction - reproduces the REAL main loop structure
//(direct RCSR read, injection check, THEN step) run repeatedly, starting
//from the exact hang snapshot, to see if the hang only emerges from this
//specific loop structure / repeated execution rather than a single step().
//@author 
//@category PDP11 Hardware
//@keybinding 
//@menupath 
//@toolbar 
//@runtime Java
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.emulator.MemoryAccessFilter;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.util.task.TaskMonitor;

public class HangReproStepper4 extends GhidraScript {
    public void run() throws Exception {
        EmulatorHelper emuHelper = new EmulatorHelper(currentProgram);

        emuHelper.writeRegister("PC", 0x19b8);
        emuHelper.writeRegister("R0", 0x47);
        emuHelper.writeRegister("R1", 0x1);
        emuHelper.writeRegister("R2", 0x0);
        emuHelper.writeRegister("R3", 0x0);
        emuHelper.writeRegister("R4", 0x0);
        emuHelper.writeRegister("R5", 0x1921);
        emuHelper.writeRegister("SP", 0x1fc);
        emuHelper.writeRegister("PS", 0xc);

        Address xbufAddr = toAddr(0xFF76);
        Address rbufAddr = toAddr(0xFF72);
        Address rcsrAddr = toAddr(0xFF70);
        Address xcsrAddr = toAddr(0xFF74);

        emuHelper.writeMemoryValue(xcsrAddr, 2, 0x0080);
        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x0000);

        emuHelper.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {
            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                Address read = space.getAddress(offset);
                if (read.equals(rbufAddr)) {
                    try {
                        byte[] c = emuHelper.readMemory(rcsrAddr, 2);
                        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(rcsrAddr, 2, val & ~0x80);
                    } catch (Exception e) { }
                }
            }
            @Override
            protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                Address written = space.getAddress(offset);
                if (written.equals(xbufAddr)) {
                    try {
                        emuHelper.writeMemoryValue(xcsrAddr, 2, 0x0000);
                    } catch (Exception e) { }
                }
            }
        });

        final int[] stepsCompleted = { 0 };
        final int maxSteps = 200;

        Thread loopThread = new Thread(() -> {
            try {
                for (int i = 0; i < maxSteps; i++) {
                    long pc = emuHelper.readRegister("PC").longValue();

                    byte[] rcsrBytes = emuHelper.readMemory(rcsrAddr, 2);
                    int rcsrVal = (rcsrBytes[0] & 0xFF) | ((rcsrBytes[1] & 0xFF) << 8);
                    boolean ready = (rcsrVal & 0x80) != 0;

                    if (!ready) {
                        // No pending queue in this test - just checking, not injecting
                    }

                    emuHelper.step(monitor);
                    stepsCompleted[0] = i + 1;
                }
            } catch (Exception e) {
                println("Loop threw: " + e);
            }
        });
        loopThread.start();
        loopThread.join(8000);

        if (loopThread.isAlive()) {
            println("CONFIRMED: real loop structure HUNG after " + stepsCompleted[0] + " completed steps.");
            println("PC at hang: 0x" + Long.toHexString(emuHelper.readRegister("PC").longValue()));
            monitor.cancel();
            loopThread.interrupt();
        } else {
            println("Loop completed all " + maxSteps + " steps normally.");
            println("Final PC: 0x" + Long.toHexString(emuHelper.readRegister("PC").longValue()));
        }

        emuHelper.dispose();
    }
}
