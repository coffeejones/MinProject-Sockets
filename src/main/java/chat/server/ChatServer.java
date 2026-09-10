package chat.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Første milepæl: accepter én klient og besvar én tekstlinje. */
public class ChatServer {
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("Server klar på port " + server.getLocalPort());
            try (Socket client = server.accept();
                 BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
                String line = input.readLine();
                if (line != null) {
                    output.write("Server modtog: " + line);
                    output.newLine();
                    output.flush();
                }
            }
        }
    }
}
