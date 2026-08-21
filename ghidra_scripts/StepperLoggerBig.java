//Compact stepper logger for long runs (up to 1M steps). Writes a short line
//per step to a log file, and includes automatic RCSR/XCSR handling (matching
//InterractiveVT52's proven logic) so it doesn't stall on simple I/O polling
//without needing real interactive input. Also detects "same PC repeating" as
//an early hang signal, so you don't have to hunt through a huge file by hand.
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
import ghidra.program.model.listing.Instruction;
import ghidra.util.task.TaskMonitor;
import java.io.FileWriter;
import java.io.PrintWriter;

public class StepperLoggerBig extends GhidraScript {
    public void run() throws Exception {
        EmulatorHelper emuHelper = new EmulatorHelper(currentProgram);

        String startPCStr = askString("Start PC", "Enter starting PC (hex, no 0x):", "0080");
        long startPC = Long.parseLong(startPCStr.trim(), 16);
        String startSPStr = askString("Start SP", "Enter starting stack SP (hex, no 0x):", "5000");
        long startSP = Long.parseLong(startSPStr.trim(), 16);
        String maxStepsStr = askString("Max Steps", "Maximum steps to run:", "1000000");
        int maxSteps = Integer.parseInt(maxStepsStr.trim());
        String logPath = askString("Log File", "Full path to save log file:", "C:/GHIDRA/big_trace.txt");

        emuHelper.writeRegister("PC", startPC);
        emuHelper.writeRegister("PS", 0x0000);
        emuHelper.writeRegister("SP", startSP);

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
                } else if (read.equals(xcsrAddr)) {
                    try {
                        byte[] c = emuHelper.readMemory(xcsrAddr, 2);
                        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(xcsrAddr, 2, val | 0x80);
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

        PrintWriter log = new PrintWriter(new FileWriter(logPath));

        // Scripted, automatic input - matches what you already confirmed works
        // manually: space first (any-key prompt), then Ctrl-O (start game).
        // Each entry only gets sent once, the first time RCSR is seen not-ready
        // AFTER the previous scripted char was consumed.
        int[] scriptedChars = { 0x20, 0x0F }; // space, then Ctrl-O
        int scriptIndex = 0;
        long lastReadyCheckStep = -1;

        long lastPc = -1;
        int samePcCount = 0;
        final int SAME_PC_THRESHOLD = 5000;

        int step;
        for (step = 0; step < maxSteps; step++) {
            long pc = emuHelper.readRegister("PC").longValue();

            byte[] rcsrNow = emuHelper.readMemory(rcsrAddr, 2);
            int rcsrNowVal = (rcsrNow[0] & 0xFF) | ((rcsrNow[1] & 0xFF) << 8);
            boolean rcsrReady = (rcsrNowVal & 0x80) != 0;
            if (!rcsrReady && scriptIndex < scriptedChars.length && step - lastReadyCheckStep > 2000) {
                emuHelper.writeMemoryValue(rbufAddr, 2, scriptedChars[scriptIndex]);
                emuHelper.writeMemoryValue(rcsrAddr, 2, 0x80);
                log.println(">>> Auto-injected scripted char #" + scriptIndex + " = 0x" + Integer.toHexString(scriptedChars[scriptIndex]) + " at step " + step + " <<<");
                log.flush();
                scriptIndex++;
                lastReadyCheckStep = step;
            }

            if (pc == lastPc) {
                samePcCount++;
                if (samePcCount == SAME_PC_THRESHOLD) {
                    log.println(">>> PC=" + Long.toHexString(pc) + " has not changed for " + SAME_PC_THRESHOLD + " steps - likely hang <<<");
                    log.flush();
                }
            } else {
                samePcCount = 0;
            }
            lastPc = pc;

            if (step % 100 == 0 || samePcCount >= SAME_PC_THRESHOLD) {
                Instruction instr = currentProgram.getListing().getInstructionAt(toAddr(pc));
                String instrText = (instr != null) ? instr.toString() : "??";
                long r0 = emuHelper.readRegister("R0").longValue() & 0xFFFF;
                long r5 = emuHelper.readRegister("R5").longValue() & 0xFFFF;
                log.println(String.format("[%d] PC=%04X %s R0=%04X R5=%04X", step, pc, instrText, r0, r5));
                if (step % 5000 == 0) log.flush();
            }

            if (samePcCount > SAME_PC_THRESHOLD * 3) {
                log.println("Stopping early - confirmed stuck at PC=" + Long.toHexString(pc) + " after " + step + " total steps.");
                break;
            }

            emuHelper.step(monitor);
        }

        log.println("Finished after " + step + " steps. Final PC=" + Long.toHexString(emuHelper.readRegister("PC").longValue()));
        log.close();
        println("Done. Log written to: " + logPath);
        emuHelper.dispose();
    }
}
