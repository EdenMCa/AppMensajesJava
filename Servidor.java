import java.io.*;
import java.net.*;
import java.util.*;

public class Servidor {

    // Lista de todos los clientes conectados
    private static List<PrintWriter> clientes = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        int puerto = 5000;
        System.out.println("Servidor iniciado en el puerto " + puerto);

        ServerSocket serverSocket = new ServerSocket(puerto);

        while (true) {
            // Esperar a que un cliente se conecte
            Socket socket = serverSocket.accept();
            System.out.println("Nuevo cliente conectado: " + socket.getInetAddress());

            // Crear un hilo para atender a ese cliente
            Thread hilo = new Thread(new ManejadorCliente(socket));
            hilo.start();
        }
    }

    // Método para enviar un mensaje a TODOS los clientes conectados
    public static synchronized void broadcast(String mensaje) {
        for (PrintWriter pw : clientes) {
            pw.println(mensaje);
        }
    }

    // Agregar un cliente a la lista
    public static synchronized void agregarCliente(PrintWriter pw) {
        clientes.add(pw);
    }

    // Eliminar un cliente de la lista cuando se desconecta
    public static synchronized void eliminarCliente(PrintWriter pw) {
        clientes.remove(pw);
    }


    // Clase interna: maneja a cada cliente en su propio hilo
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

                // Primer mensaje del cliente: su nombre
                nombre = entrada.readLine();
                agregarCliente(salida);
                broadcast(">>> " + nombre + " se unió al chat.");

                // Escuchar mensajes del cliente
                String mensaje;
while ((mensaje = entrada.readLine()) != null) {
    if (mensaje.equals("/exit")) break; 
    broadcast(nombre + ": " + mensaje);
}

            } catch (IOException e) {
                System.out.println("Cliente desconectado: " + nombre);
            } finally {
                eliminarCliente(salida);
                broadcast(">>> " + nombre + " salió del chat.");
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }
}