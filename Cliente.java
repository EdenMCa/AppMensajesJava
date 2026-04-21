import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Cliente {

    public static void main(String[] args) throws IOException {
        String host = "172.25.3.37";
        int puerto = 5000;

        Socket socket = new Socket(host, puerto);
        System.out.println("Conectado al servidor.");

        PrintWriter salida = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader entrada = new BufferedReader(
            new InputStreamReader(socket.getInputStream()));

        Scanner scanner = new Scanner(System.in);

        // Pedir nombre al usuario
        System.out.print("Ingresa tu nombre: ");
        String nombre = scanner.nextLine();
        salida.println(nombre); // Enviarlo al servidor

        // Hilo para recibir mensajes del servidor (escucha en segundo plano)
        Thread receptor = new Thread(() -> {
            try {
                String mensajeRecibido;
                while ((mensajeRecibido = entrada.readLine()) != null) {
                    System.out.println(mensajeRecibido);
                }
            } catch (IOException e) {
                System.out.println("Desconectado del servidor.");
            }
        });
        receptor.setDaemon(true);
        receptor.start();

        // Leer mensajes del usuario y enviarlos al servidor
// Leer mensajes del usuario y enviarlos al servidor
while (scanner.hasNextLine()) {
    String msg = scanner.nextLine().trim();

    // Ignorar líneas vacías
    if (msg.isEmpty()) continue;

    // Sistema de comandos — todos empiezan con /
    if (msg.startsWith("/")) {
        switch (msg.toLowerCase()) {
            case "/exit":
                salida.println("/exit");
                System.out.println("Saliendo del chat...");
                // cerrar todo
                try {
                    salida.close();
                    entrada.close();
                    socket.close();
                } catch (IOException e) {}
                System.exit(0);
                break;

            case "/ayuda":
                System.out.println("Comandos disponibles:");
                System.out.println("  /exit   → salir del chat");
                System.out.println("  /ayuda  → mostrar esta ayuda");
                break;

            default:
                System.out.println("Comando desconocido: " + msg);
                System.out.println("Escribe /ayuda para ver los comandos.");
                break;
        }
        // Los comandos NO se envían al servidor como mensajes
        continue;
    }

    // Mensaje normal — enviarlo al servidor
    salida.println(msg);
}
    }
}