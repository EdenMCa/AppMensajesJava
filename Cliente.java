import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.nio.file.*;
import java.util.Base64;

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
        if (mensajeRecibido.startsWith("/archivo:")) {
            try {
                String[] partes = mensajeRecibido.split(":", 4);
                if (partes.length < 4) continue; // Evitar errores si el mensaje está mal formado

                String remitente = partes[1];
                String nombreArchivo = partes[2];
                byte[] bytes = Base64.getDecoder().decode(partes[3]);

                File carpeta = new File("recibidos");
                if (!carpeta.exists()) carpeta.mkdirs();
                
                Files.write(Paths.get("recibidos/" + nombreArchivo), bytes);
                System.out.println("\n>>> " + remitente + " envió un archivo: " + nombreArchivo);
            } catch (Exception e) {
                System.out.println("Error al procesar archivo entrante.");
            }
        } else {
            System.out.println("\n" + mensajeRecibido);
        }
        System.out.print("> "); // Para mantener el prompt visible
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
if (msg.startsWith("/enviar ")) {
    String ruta = msg.substring(8).trim();
    File archivo = new File(ruta);

    if (!archivo.exists()) {
        System.out.println("Archivo no encontrado: " + ruta);
        continue;
    }

    // Límite de 10MB para no saturar el chat
    if (archivo.length() > 10 * 1024 * 1024) {
        System.out.println("Archivo demasiado grande (máx 10MB).");
        continue;
    }

    byte[] bytes = Files.readAllBytes(archivo.toPath());
    String base64 = Base64.getEncoder().encodeToString(bytes);

    // formato: /archivo:nombre_archivo:base64
    salida.println("/archivo:" + archivo.getName() + ":" + base64);
    System.out.println("Archivo enviado: " + archivo.getName());
    continue;
}
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
                        System.out.println("  /enviar <ruta>   → enviar un archivo");
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