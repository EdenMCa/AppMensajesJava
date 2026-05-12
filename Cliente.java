
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.Scanner;

public class Cliente {

    public static void main(String[] args) throws IOException {
        String host = "172.25.32.81";
        int puerto = 5000;

        Socket socket = new Socket(host, puerto);
        System.out.println("Conectado al servidor.");

        PrintWriter salida = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader entrada = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
        Scanner scanner = new Scanner(System.in);

        System.out.print("Ingresa tu nombre: ");
        String nombre = scanner.nextLine().trim();
        salida.println(nombre);

        // Hilo receptor — escucha mensajes del servidor
        Thread receptor = new Thread(() -> {
            try {
                String linea;
                while ((linea = entrada.readLine()) != null) {

                    if (linea.startsWith("/archivop:")) {
                        // Formato: /archivop:USUARIO:NOMBRE_ARCHIVO:BASE64
                        int c1 = linea.indexOf(":");
                        int c2 = linea.indexOf(":", c1 + 1);
                        int c3 = linea.indexOf(":", c2 + 1);

                        if (c1 == -1 || c2 == -1 || c3 == -1) {
                            continue;
                        }

                        String remitente = linea.substring(c1 + 1, c2);
                        String nombreArchivo = linea.substring(c2 + 1, c3);
                        String base64 = linea.substring(c3 + 1);

                        try {
                            byte[] bytes = Base64.getDecoder().decode(base64);
                            File carpeta = new File("recibidos");
                            carpeta.mkdirs();
                            Files.write(Paths.get("recibidos/" + nombreArchivo), bytes);

                            System.out.println("\n>>> [PRIVADO] " + remitente
                                    + " te envió: " + nombreArchivo
                                    + " (" + bytes.length / 1024 + " KB) → guardado en recibidos/");
                        } catch (Exception e) {
                            System.out.println("Error al guardar archivo privado: " + e.getMessage());
                        }

                        System.out.print("> ");
                        continue;
                    }

                    if (linea.startsWith("/archivo:")) {
                        // Formato: /archivo:USUARIO:NOMBRE_ARCHIVO:BASE64
                        int c1 = linea.indexOf(":");           // después de "/archivo"
                        int c2 = linea.indexOf(":", c1 + 1);  // después del usuario
                        int c3 = linea.indexOf(":", c2 + 1);  // después del nombre del archivo

                        if (c1 == -1 || c2 == -1 || c3 == -1) {
                            continue;
                        }

                        String remitente = linea.substring(c1 + 1, c2);
                        String nombreArchivo = linea.substring(c2 + 1, c3);
                        String base64 = linea.substring(c3 + 1);

                        try {
                            byte[] bytes = Base64.getDecoder().decode(base64);

                            File carpeta = new File("recibidos");
                            carpeta.mkdirs();
                            Files.write(Paths.get("recibidos/" + nombreArchivo), bytes);

                            System.out.println("\n>>> " + remitente
                                    + " envió: " + nombreArchivo
                                    + " (" + bytes.length / 1024 + " KB) → guardado en recibidos/");
                        } catch (Exception e) {
                            System.out.println("Error al guardar archivo: " + e.getMessage());
                        }

                    } else {
                        System.out.println("\n" + linea);
                    }

                    System.out.print("> ");
                }
            } catch (IOException e) {
                System.out.println("\nDesconectado del servidor.");
            }
        });

        receptor.setDaemon(true);
        receptor.start();

        System.out.println("Escribe /ayuda para ver los comandos.");
        System.out.print("> ");

        // Hilo principal — leer lo que escribe el usuario
        while (scanner.hasNextLine()) {
            String msg = scanner.nextLine().trim();

            if (msg.isEmpty()) {
                continue;
            }

            // Comando /enviarp <usuario> <ruta>  → archivo privado
            if (msg.startsWith("/enviarp ")) {
                String resto = msg.substring(9).trim();
                int espacio = resto.indexOf(" ");

                if (espacio == -1) {
                    System.out.println("Uso: /enviarp <usuario> <ruta>");
                    System.out.print("> ");
                    continue;
                }

                String destino = resto.substring(0, espacio);
                String ruta = resto.substring(espacio + 1).trim();
                File archivo = new File(ruta);

                if (!archivo.exists()) {
                    System.out.println("Archivo no encontrado: " + ruta);
                    System.out.print("> ");
                    continue;
                }

                if (archivo.length() > 10 * 1024 * 1024) {
                    System.out.println("Archivo demasiado grande (máx 10 MB).");
                    System.out.print("> ");
                    continue;
                }

                byte[] bytes = Files.readAllBytes(archivo.toPath());
                String base64 = Base64.getEncoder().encodeToString(bytes);

                // Formato: /archivop:destinatario:nombre_archivo:BASE64
                salida.println("/archivop:" + destino + ":" + archivo.getName() + ":" + base64);
                System.out.print("> ");
                continue;
            }

            // Comando /enviar (se maneja antes del switch general)
            if (msg.startsWith("/enviar ")) {
                String ruta = msg.substring(8).trim();
                File archivo = new File(ruta);

                if (!archivo.exists()) {
                    System.out.println("Archivo no encontrado: " + ruta);
                    System.out.print("> ");
                    continue;
                }

                if (archivo.length() > 10 * 1024 * 1024) {
                    System.out.println("Archivo demasiado grande (máx 10 MB).");
                    System.out.print("> ");
                    continue;
                }

                byte[] bytes = Files.readAllBytes(archivo.toPath());
                String base64 = Base64.getEncoder().encodeToString(bytes);

                // Formato enviado: /archivo:nombre_archivo:BASE64
                salida.println("/archivo:" + archivo.getName() + ":" + base64);
                System.out.println("✓ Archivo enviado: " + archivo.getName());
                System.out.print("> ");
                continue;
            }
            if (msg.startsWith("/msg ")) {
                salida.println(msg);
                System.out.print("> ");
                continue;
            }
            // Otros comandos con /
            if (msg.startsWith("/")) {
                switch (msg.toLowerCase()) {
                    case "/exit":
                        salida.println("/exit");
                        System.out.println("Saliendo del server...");
                        try {
                            salida.close();
                            entrada.close();
                            socket.close();
                        } catch (IOException e) {
                        }
                        System.exit(0);
                        break;

                    case "/ayuda":
                        System.out.println("Comandos disponibles:");
                        System.out.println("  /msg <usuario> <texto>  → enviar mensaje privado");
                        System.out.println("  /enviar <ruta>          → enviar un archivo");
                        System.out.println("  /enviarp <usuario> <ruta> → enviar archivo privado");
                        System.out.println("  /exit                   → salir del chat");
                        System.out.println("  /usuarios               → ver quién está conectado");
                        System.out.println("  /ayuda                  → mostrar esta ayuda");

                        break;
                    case "/usuarios":
                        salida.println("/usuarios");
                        break;
                    default:
                        System.out.println("Comando desconocido. Escribe /ayuda.");
                        break;
                }
                System.out.print("> ");
                continue;
            }

            // Mensaje normal
            salida.println(msg);
            System.out.print("> ");
        }
    }
}
