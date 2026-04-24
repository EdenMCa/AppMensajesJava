
import java.io.*;
import java.net.*;
import java.util.*;

public class Servidor {

    // Mapa cliente → nombre, para saber quién es quién
    private static Map<PrintWriter, String> clientes = new HashMap<>();

    public static void main(String[] args) throws IOException {
        int puerto = 5000;
        System.out.println("Servidor iniciado en el puerto " + puerto);

        ServerSocket serverSocket = new ServerSocket(puerto);

        while (true) {
            Socket socket = serverSocket.accept();
            System.out.println("Nueva conexión: " + socket.getInetAddress());
            new Thread(new ManejadorCliente(socket)).start();
        }
    }

    // Buscar el PrintWriter de un cliente por su nombre
    public static synchronized PrintWriter buscarCliente(String nombreBuscado) {
        for (Map.Entry<PrintWriter, String> entry : clientes.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(nombreBuscado)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // Enviar a TODOS los clientes
    public static synchronized void broadcast(String mensaje) {
        for (PrintWriter pw : clientes.keySet()) {
            pw.println(mensaje);
        }
    }

    // Enviar a todos MENOS al remitente
    public static synchronized void broadcastExcepto(String mensaje, PrintWriter excepto) {
        for (PrintWriter pw : clientes.keySet()) {
            if (pw != excepto) {
                pw.println(mensaje);
            }
        }
    }

    public static synchronized void agregarCliente(PrintWriter pw, String nombre) {
        clientes.put(pw, nombre);
    }

    public static synchronized void eliminarCliente(PrintWriter pw) {
        clientes.remove(pw);
    }

    static class ManejadorCliente implements Runnable {

        private Socket socket;
        private PrintWriter salida;
        private String nombre;

        public ManejadorCliente(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                BufferedReader entrada = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                salida = new PrintWriter(socket.getOutputStream(), true);

                nombre = entrada.readLine();
                if (nombre == null || nombre.isBlank()) {
                    socket.close();
                    return;
                }

                agregarCliente(salida, nombre);
                broadcast(">>> " + nombre + " se unió al chat.");
                System.out.println(nombre + " conectado.");

                String mensaje;
                while ((mensaje = entrada.readLine()) != null) {
                    if (mensaje.equals("/exit")) {
                        break;
                    }
                    // ---  mensaje privado ---
                    if (mensaje.startsWith("/msg ")) {
                        // Formato: /msg destinatario contenido del mensaje
                        String resto = mensaje.substring(5).trim();
                        int espacio = resto.indexOf(" ");

                        if (espacio == -1) {
                            salida.println("Uso: /msg <usuario> <mensaje>");
                            continue;
                        }

                        String destino = resto.substring(0, espacio);
                        String texto = resto.substring(espacio + 1);

                        PrintWriter pwDestino = buscarCliente(destino);

                        if (pwDestino == null) {
                            salida.println("Usuario '" + destino + "' no está conectado.");
                        } else if (pwDestino == salida) {
                            salida.println("No puedes enviarte mensajes a ti mismo.");
                        } else {
                            // Enviar al destinatario
                            pwDestino.println("[Privado de " + nombre + "]: " + texto);
                            // Confirmación al emisor
                            salida.println("[Privado para " + destino + "]: " + texto);
                            System.out.println("Privado: " + nombre + " → " + destino);
                        }
                        continue;
                    }

                    if (mensaje.equals("/usuarios")) {
                        StringBuilder lista = new StringBuilder("Usuarios conectados: ");
                        synchronized (clientes) {
                            for (String n : clientes.values()) {
                                lista.append(n).append(", ");
                            }
                        }
                        salida.println(lista.toString());
                        continue;
                    }
                    if (mensaje.startsWith("/archivo:")) {
                        // Formato recibido: /archivo:nombre_archivo:BASE64
                        // Primer split en 3 partes exactas
                        int primerColon = mensaje.indexOf(":");
                        int segundoColon = mensaje.indexOf(":", primerColon + 1);

                        if (segundoColon == -1) {
                            continue; // Mensaje mal formado
                        }
                        String nombreArchivo = mensaje.substring(primerColon + 1, segundoColon);
                        String base64 = mensaje.substring(segundoColon + 1);

                        // Reenviar a todos MENOS al remitente
                        // Formato enviado: /archivo:NOMBRE_USUARIO:NOMBRE_ARCHIVO:BASE64
                        broadcastExcepto(
                                "/archivo:" + nombre + ":" + nombreArchivo + ":" + base64,
                                salida
                        );

                        System.out.println(nombre + " envió archivo: " + nombreArchivo);

                    } else {
                        broadcast(nombre + ": " + mensaje);
                    }
                }

            } catch (IOException e) {
                System.out.println("Conexión perdida: " + nombre);
            } finally {
                eliminarCliente(salida);
                if (nombre != null) {
                    broadcast(">>> " + nombre + " salió del chat.");
                    System.out.println(nombre + " desconectado.");
                }
                try {
                    socket.close();
                } catch (IOException e) {
                }
            }
        }
    }
}
