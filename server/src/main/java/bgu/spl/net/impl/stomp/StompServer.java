package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;

public class StompServer {

    public static void main(String[] args) {
        // args[0] = port, args[1] = server type (tpc/reactor)

        if (args.length < 2) {
            System.out.println("Usage: StompServer <port> <server_type>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String serverType = args[1];

        if (serverType.equals("tpc")) {
            Server.threadPerClient(
                    port,
                    () -> new StompMessagingProtocolImpl(), 
                    () -> new StompEncoderDecoder()      
            ).serve();
        } else if (serverType.equals("reactor")) {
            Server.reactor(
                    Runtime.getRuntime().availableProcessors(),
                    port,
                    () -> new StompMessagingProtocolImpl(), 
                    () -> new StompEncoderDecoder()         
            ).serve();
        } else {
            System.out.println("Unknown server type. Use 'tpc' or 'reactor'.");
        }
    }
}