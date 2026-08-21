//Second isolated reproduction - same exact register snapshot as before, but
//this time WITH a MemoryAccessFilter attached (matching InterractiveVT52's
//own processWrite logic), to see if the hang lives in OUR callback code
//rather than the pcode itself (which the first test already cleared).
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

public class HangReproStepper2 extends GhidraScript {
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
        emuHelper.writeMemoryValue(toAddr(0xFF74), 2, 0x0080); // XCSR ready

        emuHelper.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {
            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                // intentionally empty - isolating processWrite specifically
            }

            @Override
            protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                Address written = space.getAddress(offset);
                println("processWrite fired for address: " + written);
                if (written.equals(xbufAddr)) {
                    println("  -> XBUF write, value=" + String.format("0x%02X", values[0] & 0xFF));
                    try {
                        emuHelper.writeMemoryValue(toAddr(0xFF74), 2, 0x0000);
                        println("  -> cleared XCSR OK");
                    } catch (Exception ex) {
                        println("  -> exception clearing XCSR: " + ex);
                    }
                }
                println("processWrite finished normally.");
            }
        });

        println("Before step: PC=" + Long.toHexString(emuHelper.readRegister("PC").longValue()));

        long timeoutMs = 5000;
        Thread stepThread = new Thread(() -> {
            try {
                emuHelper.step(monitor);
            } catch (Exception e) {
                println("step() threw: " + e);
            }
        });
        stepThread.start();
        stepThread.join(timeoutMs);

        if (stepThread.isAlive()) {
            println("CONFIRMED: step() with MemoryAccessFilter attached did NOT return within " + timeoutMs + "ms.");
            monitor.cancel();
            stepThread.interrupt();
        } else {
            println("step() returned normally.");
            println("After step: PC=" + Long.toHexString(emuHelper.readRegister("PC").longValue()));
        }

        emuHelper.dispose();
    }
}
