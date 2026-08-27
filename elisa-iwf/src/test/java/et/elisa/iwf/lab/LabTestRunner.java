package et.elisa.iwf.lab;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer;

/**
 * CLI test runner invoked by lab-run.sh.
 * Args: round imsi command expectedCode
 * Exit 0 = pass, 1 = fail.
 */
public final class LabTestRunner {

    private static final String DRA_HOST = "127.0.0.1";
    private static final int DRA_PORT = 3870;
    private static final int SRC_PORT = 38691;
    private static final long ANSWER_TIMEOUT_MS = 10_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: LabTestRunner <round> <imsi> <command> <expectedCode>");
            System.exit(1);
        }
        int round = Integer.parseInt(args[0]);
        String imsi = args[1];
        String command = args[2];
        long expectedCode = Long.parseLong(args[3]);

        System.out.printf("[Round %d] cmd=%s imsi=%s expect=%d%n", round, command, imsi, expectedCode);

        try (DiameterTestClient client = new DiameterTestClient(DRA_HOST, DRA_PORT, SRC_PORT)) {
            client.start();
            System.out.println("[Round " + round + "] Waiting for CER/CEA handshake...");
            if (!client.waitForCer(15_000)) {
                System.err.println("[Round " + round + "] FAIL: CER/CEA handshake timeout");
                System.exit(1);
            }
            System.out.println("[Round " + round + "] CER/CEA OK, sending " + command + "...");

            switch (command) {
                case "ULR" -> { client.sendUlr(imsi, "45201"); sleepAndCheck(client, round); }
                case "AIR" -> { client.sendAir(imsi, 3); sleepAndCheck(client, round); }
                case "PUR" -> { client.sendPur(imsi); sleepAndCheck(client, round); }
                case "STRESS" -> runStress(client, round);
                case "MIXED" -> runMixed(client, imsi, round);
                case "MULTI" -> runMulti(client, imsi, round);

                // Sh
                case "SH_UDR" -> { client.sendShUdr(imsi); sleepAndCheck(client, round); }

                // Rx
                case "RX_AAR" -> { client.sendRxAar(); sleepAndCheck(client, round); }

                // Gx
                case "GX_CCR" -> { client.sendGxCcr(); sleepAndCheck(client, round); }

                // Cx/Dx
                case "CX_UAR" -> { client.sendCxUar("sip:" + imsi + "@ims.mnc001.mcc452.3gppnetwork.org"); sleepAndCheck(client, round); }

                // S13
                case "S13_ECR" -> { client.sendS13Ecr(); sleepAndCheck(client, round); }

                // SLh
                case "SLH_RIR" -> { client.sendSlhRir(imsi); sleepAndCheck(client, round); }

                // SLg
                case "SLG_PLR" -> { client.sendSlgPlr(imsi); sleepAndCheck(client, round); }

                // S6c
                case "S6C_SRR" -> { client.sendS6cSrr(imsi); sleepAndCheck(client, round); }

                // SWx
                case "SWX_MAR" -> { client.sendSwxMar(imsi); sleepAndCheck(client, round); }

                default -> {
                    System.err.println("[Round " + round + "] Unknown command: " + command);
                    System.exit(1);
                }
            }
        }
    }

    private static void sleepAndCheck(DiameterTestClient client, int round) throws InterruptedException {
        Thread.sleep(ANSWER_TIMEOUT_MS);
        if (client.answersReceived() > 0) {
            System.out.println("[Round " + round + "] PASS: Got answer(s)");
            System.exit(0);
        } else {
            System.err.println("[Round " + round + "] FAIL: No answers received");
            System.exit(1);
        }
    }

    private static void runStress(DiameterTestClient client, int round) throws Exception {
        for (int i = 0; i < 10; i++) {
            client.sendUlr("4520402" + String.format("%03d", 1 + i), "45201");
        }
        Thread.sleep(ANSWER_TIMEOUT_MS);
        long ans = client.answersReceived();
        if (ans >= 10) {
            System.out.println("[Round " + round + "] PASS: " + ans + " answers for 10 requests");
            System.exit(0);
        } else {
            System.err.println("[Round " + round + "] FAIL: Only " + ans + " answers for 10 requests");
            System.exit(1);
        }
    }

    private static void runMixed(DiameterTestClient client, String imsi, int round) throws Exception {
        client.sendUlr(imsi, "45201");
        Thread.sleep(500);
        client.sendAir(imsi, 1);
        Thread.sleep(500);
        client.sendPur(imsi);
        Thread.sleep(ANSWER_TIMEOUT_MS);
        long ans = client.answersReceived();
        if (ans >= 3) {
            System.out.println("[Round " + round + "] PASS: " + ans + " answers for 3 commands");
            System.exit(0);
        } else {
            System.err.println("[Round " + round + "] FAIL: Only " + ans + " answers for 3 commands");
            System.exit(1);
        }
    }

    private static void runMulti(DiameterTestClient client, String imsi, int round) throws Exception {
        int expected = 9;
        client.sendShUdr(imsi);
        Thread.sleep(300);
        client.sendRxAar();
        Thread.sleep(300);
        client.sendGxCcr();
        Thread.sleep(300);
        client.sendCxUar("sip:" + imsi + "@ims.mnc001.mcc452.3gppnetwork.org");
        Thread.sleep(300);
        client.sendS13Ecr();
        Thread.sleep(300);
        client.sendSlhRir(imsi);
        Thread.sleep(300);
        client.sendSlgPlr(imsi);
        Thread.sleep(300);
        client.sendS6cSrr(imsi);
        Thread.sleep(300);
        client.sendSwxMar(imsi);
        Thread.sleep(ANSWER_TIMEOUT_MS);
        long ans = client.answersReceived();
        if (ans >= expected) {
            System.out.println("[Round " + round + "] PASS: " + ans + " answers for " + expected + " commands");
            System.exit(0);
        } else {
            System.err.println("[Round " + round + "] FAIL: Only " + ans + " answers for " + expected + " commands");
            System.exit(1);
        }
    }
}
