import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.Scanner;

public class Cliente {

    private static Socket socket;
    private static PrintWriter salida;
    private static BufferedReader entrada;
    private static String nombre;
    private static volatile boolean conectado = false;
    private static volatile boolean reconectando = false;

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Ingresa tu nombre: ");
        nombre = scanner.nextLine().trim();
        if (nombre.isEmpty()) {
            System.out.println("Nombre vacío.");
            return;
        }

        if (!conectarConFailover()) {
            System.out.println("No hay servidores disponibles.");
            return;
        }

        lanzarHiloReceptor();

        System.out.println("\nEscribe /ayuda para ver los comandos.");
        System.out.print("> ");

        // Loop principal
        while (scanner.hasNextLine()) {
            String msg = scanner.nextLine().trim();
            if (msg.isEmpty()) {
                System.out.print("> ");
                continue;
            }

            // Si la conexión se perdió, esperar a que se reconecte
            if (!conectado) {
                System.out.println("Sin conexión. Reintentando");
                if (!conectarConFailover()) {
                    System.out.println("No hay servidores disponibles.");
                    System.exit(1);
                }
                lanzarHiloReceptor();
            }

            try {
                procesarComando(msg);
            } catch (IOException e) {
                System.out.println("Error de conexión: " + e.getMessage());
                conectado = false;
            }
        }
    }

    // -------------------- CONEXIÓN CON FAILOVER --------------------

    private static boolean conectarConFailover() {
        reconectando = true;
        for (int intento = 1; intento <= 3; intento++) {
            for (ServidoresConfig.ServidorInfo srv : ServidoresConfig.SERVIDORES) {
                System.out.print("Intento " + intento + ": probando " + srv.host + ":" + srv.puerto + "... ");
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(srv.host, srv.puerto), 2000);
                    salida = new PrintWriter(socket.getOutputStream(), true);
                    entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    salida.println(nombre);

                    conectado = true;
                    reconectando = false;
                    System.out.println("Conectado");
                    return true;
                } catch (IOException e) {
                    System.out.println("Falló");
                }
            }
            // Esperar un poco antes de reintentar todos
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
        }
        reconectando = false;
        return false;
    }

    // -------------------- HILO RECEPTOR --------------------

    private static void lanzarHiloReceptor() {
        Thread receptor = new Thread(() -> {
            try {
                String linea;
                while ((linea = entrada.readLine()) != null) {

                    // El servidor pide reconexión (maestro original volvió)
                    if (linea.equals("/reconectar")) {
                        System.out.println("\n** El servidor pide reconectar al maestro primario **");
                        try {
                            socket.close();
                        } catch (IOException ignore) {
                        }
                        conectado = false;
                        // Esperar un momento y reconectar
                        Thread.sleep(2000);
                        if (conectarConFailover()) {
                            lanzarHiloReceptor();
                        }
                        return;
                    }

                    if (linea.startsWith("/archivop:")) {
                        recibirArchivo(linea, true);
                    } else if (linea.startsWith("/archivo:")) {
                        recibirArchivo(linea, false);
                    } else {
                        System.out.println("\n" + linea);
                    }
                    System.out.print("> ");
                }
            } catch (IOException e) {
                // Conexión cerrada
            } catch (InterruptedException e) {
                return;
            }

            // Si llegamos aquí, la conexión se perdió
            conectado = false;
            if (!reconectando) {
                System.out.println("\n Conexión perdida. Reintentando automáticamente...");
                if (conectarConFailover()) {
                    lanzarHiloReceptor();
                } else {
                    System.out.println("No hay servidores disponibles.");
                    System.exit(1);
                }
            }
        }, "Receptor");
        receptor.setDaemon(true);
        receptor.start();
    }

    // -------------------- PROCESAR COMANDOS --------------------

    private static void procesarComando(String msg) throws IOException {
        // /enviarp <usuario> <ruta>
        if (msg.startsWith("/enviarp ")) {
            String resto = msg.substring(9).trim();
            int sp = resto.indexOf(" ");
            if (sp == -1) {
                System.out.println("Uso: /enviarp <usuario> <ruta>");
                System.out.print("> ");
                return;
            }
            String dest = resto.substring(0, sp);
            String ruta = resto.substring(sp + 1).trim();
            enviarArchivo(dest, ruta);
            System.out.print("> ");
            return;
        }

        // /enviar <ruta>
        if (msg.startsWith("/enviar ")) {
            enviarArchivo(null, msg.substring(8).trim());
            System.out.print("> ");
            return;
        }

        // /msg <usuario> <texto>
        if (msg.startsWith("/msg ")) {
            salida.println(msg);
            System.out.print("> ");
            return;
        }

        // Otros comandos sin argumentos
        if (msg.startsWith("/")) {
            switch (msg.toLowerCase()) {
                case "/exit":
                    salida.println("/exit");
                    System.out.println("Saliendo del server...");
                    try {
                        socket.close();
                    } catch (IOException ignore) {
                    }
                    System.exit(0);
                    break;
                case "/ayuda":
                    System.out.println("Comandos disponibles:");
                    System.out.println("  /msg <usuario> <texto>     → mensaje privado");
                    System.out.println("  /enviar <ruta>             → enviar archivo a todos");
                    System.out.println("  /enviarp <usuario> <ruta>  → enviar archivo privado");
                    System.out.println("  /usuarios                  → ver conectados");
                    System.out.println("  /exit                      → salir del chat");
                    System.out.println("  /ayuda                     → esta ayuda");
                    break;
                case "/usuarios":
                    salida.println("/usuarios");
                    break;
                default:
                    System.out.println("Comando desconocido. Escribe /ayuda.");
            }
            System.out.print("> ");
            return;
        }

        // Mensaje normal
        salida.println(msg);
        System.out.print("> ");
    }

    // -------------------- ENVÍO DE ARCHIVOS --------------------

    private static void enviarArchivo(String dest, String ruta) throws IOException {
        File archivo = new File(ruta);
        if (!archivo.exists()) {
            System.out.println("Archivo no encontrado: " + ruta);
            return;
        }
        if (archivo.length() > 10 * 1024 * 1024) {
            System.out.println("Archivo demasiado grande (máx 10 MB).");
            return;
        }

        byte[] bytes = Files.readAllBytes(archivo.toPath());
        String b64 = Base64.getEncoder().encodeToString(bytes);

        if (dest == null) {
            salida.println("/archivo:" + archivo.getName() + ":" + b64);
            System.out.println("✓ Archivo enviado a todos: " + archivo.getName());
        } else {
            salida.println("/archivop:" + dest + ":" + archivo.getName() + ":" + b64);
        }
    }

    private static void recibirArchivo(String linea, boolean privado) {
        try {
            int c1 = linea.indexOf(":");
            int c2 = linea.indexOf(":", c1 + 1);
            int c3 = linea.indexOf(":", c2 + 1);
            if (c3 == -1)
                return;

            String remitente = linea.substring(c1 + 1, c2);
            String archivo = linea.substring(c2 + 1, c3);
            String b64 = linea.substring(c3 + 1);

            byte[] bytes = Base64.getDecoder().decode(b64);
            File carpeta = new File("recibidos");
            carpeta.mkdirs();
            Files.write(Paths.get("recibidos/" + archivo), bytes);

            String marca = privado ? "[PRIVADO] " : "";
            System.out.println("\n>>> " + marca + remitente + " envió: " + archivo
                    + " (" + bytes.length / 1024 + " KB) → guardado en recibidos/");
        } catch (Exception e) {
            System.out.println("Error al guardar archivo: " + e.getMessage());
        }
    }
}