package bgu.spl.net.impl.stomp;

import bgu.spl.net.impl.data.Database;
import bgu.spl.net.srv.Server;

import java.util.Scanner;

public class StompServer {

    private static void startAdminConsole(Database db) {
        Thread t = new Thread(() -> {
            try {
                Scanner sc = new Scanner(System.in);
                while (true) {
                    if (!sc.hasNextLine()) {
                        return;
                    }
                    String line = sc.nextLine().trim();
                    if (line.equalsIgnoreCase("report")) {
                        db.printReport();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "AdminConsole");
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) {

        if (args.length < 2) {
            System.out.println("Usage: StompServer <port> <server_type>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String serverType = args[1];

        startAdminConsole(Database.getInstance());

        if (serverType.equals("tpc")) {
            Server.threadPerClient(
                    port,
                    StompMessagingProtocolImpl::new,
                    StompEncoderDecoder::new
            ).serve();
        } else if (serverType.equals("reactor")) {
            Server.reactor(
                    Runtime.getRuntime().availableProcessors(),
                    port,
                    StompMessagingProtocolImpl::new,
                    StompEncoderDecoder::new
            ).serve();
        } else {
            System.out.println("Unknown server type. Use 'tpc' or 'reactor'.");
        }
    }
}
