package tj.activbank.qr;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.SunmiPrinterService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Печать чека на встроенном принтере Sunmi.
 *
 * Веб-часть вызывает Capacitor.Plugins.SunmiPrinter.printReceipt({...}).
 * Мост держит соединение со службой печати устройства (InnerPrinterService)
 * и отправляет ей строки чека. Бэкенд в печати не участвует.
 */
@CapacitorPlugin(name = "SunmiPrinter")
public class SunmiPrinter extends Plugin {

    private static final int WIDTH = 32;          // символов в строке (58 мм)
    private SunmiPrinterService printer;

    private final InnerPrinterCallback connection = new InnerPrinterCallback() {
        @Override
        protected void onConnected(SunmiPrinterService service) {
            printer = service;
        }

        @Override
        protected void onDisconnected() {
            printer = null;
        }
    };

    @Override
    public void load() {
        bind();
    }

    private void bind() {
        try {
            InnerPrinterManager.getInstance().bindService(getContext(), connection);
        } catch (Exception ignored) {
            printer = null;
        }
    }

    @Override
    protected void handleOnDestroy() {
        try {
            InnerPrinterManager.getInstance().unBindService(getContext(), connection);
        } catch (Exception ignored) {
        }
    }

    /** Есть ли на устройстве встроенный принтер и подключён ли он. */
    @PluginMethod
    public void isAvailable(PluginCall call) {
        if (printer == null) bind();
        JSObject res = new JSObject();
        res.put("available", printer != null);
        String serial = null;
        try {
            if (printer != null) serial = printer.getPrinterSerialNo();
        } catch (Exception ignored) {
        }
        res.put("serial", serial);
        call.resolve(res);
    }

    /**
     * printReceipt({
     *   header, merchant, terminal, amount, status,
     *   rows: [{ k, v }], qr, footer
     * })
     */
    @PluginMethod
    public void printReceipt(PluginCall call) {
        if (printer == null) bind();
        if (printer == null) {
            call.reject("printer-unavailable");
            return;
        }
        try {
            printer.enterPrinterBuffer(true);

            center();
            text(call.getString("header", "ActivBank QR"), 30f, true);
            String merchant = call.getString("merchant", "");
            if (!merchant.isEmpty()) text(merchant, 24f, false);
            String terminal = call.getString("terminal", "");
            if (!terminal.isEmpty()) text(terminal, 22f, false);

            divider();

            left();
            JSONArray rows = call.getArray("rows", new JSONArray());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                text(pair(row.optString("k", ""), row.optString("v", "")), 24f, false);
            }

            divider();

            center();
            String status = call.getString("status", "");
            if (!status.isEmpty()) text(status, 24f, false);
            String amount = call.getString("amount", "");
            if (!amount.isEmpty()) text(amount, 40f, true);

            String qr = call.getString("qr", "");
            if (!qr.isEmpty()) {
                printer.lineWrap(1, null);
                printer.printQRCode(qr, 6, 2, null);
                printer.lineWrap(1, null);
            }

            String footer = call.getString("footer", "");
            if (!footer.isEmpty()) text(footer, 20f, false);

            printer.lineWrap(4, null);
            printer.exitPrinterBuffer(true);

            try {
                printer.cutPaper(null);   // только на устройствах с отрезчиком
            } catch (Exception ignored) {
            }

            JSObject res = new JSObject();
            res.put("printed", true);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("print-failed", e);
        }
    }

    /* ── низкоуровневые помощники ─────────────────────────────── */

    private void text(String value, float size, boolean bold) throws Exception {
        printer.setFontSize(size, null);
        printer.setPrinterStyle(1002 /* ENABLE_BOLD */, bold ? 1 : 0);
        printer.printTextWithFont(value + "\n", null, size, null);
    }

    private void center() throws Exception {
        printer.setAlignment(1, null);
    }

    private void left() throws Exception {
        printer.setAlignment(0, null);
    }

    private void divider() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < WIDTH; i++) line.append('-');
        printer.setAlignment(0, null);
        printer.printTextWithFont(line + "\n", null, 22f, null);
    }

    /** «Ключ ........ значение» — выравнивание по ширине чека. */
    private String pair(String key, String value) {
        int space = WIDTH - key.length() - value.length();
        if (space < 1) return key + " " + value;
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < space; i++) pad.append(' ');
        return key + pad + value;
    }
}
