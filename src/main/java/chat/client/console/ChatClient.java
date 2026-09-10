package chat.client.console;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Første milepæl: send konsolinput og vis serverens svar. */
public class ChatClient {
    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5555;
        try (Socket socket = new Socket(host, port);
             BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            System.out.println("Skriv en besked:");
            String line = console.readLine();
            if (line != null) {
                output.write(line);
                output.newLine();
                output.flush();
                System.out.println(input.readLine());
            }
        }
    }
}
