(function() {
    let inputEl = document.querySelector('input[data-value*="binding:\'debugInput\'"]');
    let buttonEl = document.querySelector('button[data-value*="binding:\'debugEnter\'"]');
    let clearEl = document.querySelector('button[data-value*="binding:\'clear\'"]');
    let textarea = document.querySelector('textarea[data-value*="binding:\'print\'"]');

    if (!inputEl || !buttonEl || !textarea || !clearEl) {
        console.error("DOM mismatch. Check your frame context target.");
        return;
    }

    console.log("🟢 Starting Registers + RAM dump sequence...");

    let completeLog = [];
    let currentAddr = 0; 
    const targetLimit = 0x10000; // 64KB target boundary
    const stepBytes = 1024; // Linear steps of 1024 bytes (2000 Octal)

    // Helper function to simulate pressing Enter on a command string
    function submitCommand(commandText, callback) {
        clearEl.click();

        setTimeout(() => {
            inputEl.value = commandText;
            inputEl.dispatchEvent(new Event('input', { bubbles: true }));
            inputEl.dispatchEvent(new Event('change', { bubbles: true }));

            let enterOptions = { key: "Enter", keyCode: 13, which: 13, code: "Enter", bubbles: true, cancelable: true };
            inputEl.dispatchEvent(new KeyboardEvent('keydown', enterOptions));
            inputEl.dispatchEvent(new KeyboardEvent('keypress', enterOptions));
            
            buttonEl.click();
            
            inputEl.dispatchEvent(new KeyboardEvent('keyup', enterOptions));

            // Wait for the virtual CPU to safely paint the text output
            setTimeout(callback, 850);
        }, 50);
    }

    // Step 1: Capture the CPU registers first
    console.log("Capturing CPU Registers...");
    submitCommand("r", () => {
        completeLog.push("=== PDP-11 CPU REGISTERS ===");
        completeLog.push(textarea.value.trim());
        completeLog.push("\n============================\n");
        
        // Step 2: Begin the sequential RAM dump loop
        triggerNextMemoryCycle();
    });

    // Step 2 Loop: Handle the memory blocks
    function triggerNextMemoryCycle() {
        if (currentAddr >= targetLimit) {
            compileAndSave();
            return;
        }

        let octStart = currentAddr.toString(8);
        let octEnd = (currentAddr + stepBytes).toString(8); 
        
        console.log(`Invoking memory range: db ${octStart} ${octEnd}`);
        
        submitCommand(`db ${octStart} ${octEnd}`, () => {
            completeLog.push(`\n=== RANGE OCTAL ${octStart} - ${octEnd} ===`);
            completeLog.push(textarea.value.trim());
            
            currentAddr += stepBytes;
            triggerNextMemoryCycle();
        });
    }

    function compileAndSave() {
        let blob = new Blob([completeLog.join('\n')], {type: "text/plain"});
        let link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = "pdp11_regs_and_memory_dump.txt";
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        console.log("🏁 Dump complete! Output saved cleanly to pdp11_regs_and_memory_dump.txt");
    }
})();
