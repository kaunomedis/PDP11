//Isolated reproduction of the OK6g/MOVB hang seen in InterractiveVT52
//Sets exact register state from the real hang, then steps ONE instruction
//watching PC before/after and the computed source address directly.
//@author 
//@category PDP11 Hardware
//@keybinding 
//@menupath 
//@toolbar 
//@runtime Java
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import ghidra.util.task.TaskMonitor;

public class HangReproStepper extends GhidraScript {
    public void run() throws Exception {
        EmulatorHelper emu = new EmulatorHelper(currentProgram);

        // Exact snapshot from the real hang
        emu.writeRegister("PC", 0x19b8);
        emu.writeRegister("R0", 0x47);
        emu.writeRegister("R1", 0x1);
        emu.writeRegister("R2", 0x0);
        emu.writeRegister("R3", 0x0);
        emu.writeRegister("R4", 0x0);
        emu.writeRegister("R5", 0x1921);
        emu.writeRegister("SP", 0x1fc);
        emu.writeRegister("PS", 0xc);

        println("Before step: PC=" + Long.toHexString(emu.readRegister("PC").longValue()));
        println("WORD_0218 value = " + Long.toHexString(
            emu.readMemory(toAddr(0x218), 2)[0] & 0xFF | ((emu.readMemory(toAddr(0x218), 2)[1] & 0xFF) << 8)));

        long startTime = System.currentTimeMillis();
        long timeoutMs = 5000; // 5 second safety timeout so THIS script doesn't hang forever too

        Thread stepThread = new Thread(() -> {
            try {
                emu.step(monitor);
            } catch (Exception e) {
                println("step() threw: " + e);
            }
        });
        stepThread.start();
        stepThread.join(timeoutMs);

        if (stepThread.isAlive()) {
            println("CONFIRMED: step() did NOT return within " + timeoutMs + "ms - genuine hang reproduced in isolation.");
            monitor.cancel();
            stepThread.interrupt();
        } else {
            long elapsed = System.currentTimeMillis() - startTime;
            println("step() returned normally after " + elapsed + "ms.");
            println("After step: PC=" + Long.toHexString(emu.readRegister("PC").longValue()));
            println("R5 after: " + Long.toHexString(emu.readRegister("R5").longValue()));
        }

        emu.dispose();
    }
}
