import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Servidor {

    private static int miPuerto;
    private static boolean soyPrimario;          
    private static volatile boolean soyMaestroActivo;  

    private static final Map<PrintWriter, String> clientes = new ConcurrentHashMap<>();
    private static final List<PrintWriter> esclavosConectados = new CopyOnWriteArrayList<>();

    private static volatile long ultimoHeartbeat = 0;
    private static final long TIMEOUT_HEARTBEAT_MS = 9000; //Tiempo sin heartbeat para considerar al maestro caído

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Uso: java Servidor <puerto>");
            System.out.println("Ej:  java Servidor 5000   (maestro primario)");
            System.out.println("     java Servidor 5001   (esclavo)");
            return;
        }

        miPuerto = Integer.parseInt(args[0]);
        int miIndice = ServidoresConfig.getIndice(miPuerto);

        if (miIndice == -1) {
            System.out.println("Puerto " + miPuerto + " no está en ServidoresConfig.");
            return;
        }

        soyPrimario = (miIndice == 0);
        soyMaestroActivo = soyPrimario;

        System.out.println("====================================");
        System.out.println(" Servidor iniciado en puerto " + miPuerto);
        System.out.println(" Rol inicial: " + (soyMaestroActivo ? "MAESTRO" : "ESCLAVO"));
        System.out.println("====================================");

        if (soyMaestroActivo) {
            iniciarHilosDeMaestro();
        } else {
            iniciarHilosDeEsclavo();
        }

        ServerSocket serverSocket = new ServerSocket(miPuerto);
        while (true) {
            Socket socket = serverSocket.accept();
            new Thread(new ManejadorConexion(socket)).start();
        }
    }

    // Maestro
    private static void iniciarHilosDeMaestro() {
        new Thread(() -> {
            while (soyMaestroActivo) {
                try {
                    Thread.sleep(3000);
                    for (PrintWriter pw : esclavosConectados) {
                        try {
                            pw.println("/heartbeat");
                        } catch (Exception ignore) {}
                    }
                } catch (InterruptedException e) { break; }
            }
        }, "EnviarHeartbeat").start();

        if (!soyPrimario) {
            new Thread(() -> {
                ServidoresConfig.ServidorInfo primario = ServidoresConfig.getMaestroPrimario();
                while (soyMaestroActivo) {
                    try {
                        Thread.sleep(5000);
                        try (Socket test = new Socket()) {
                            test.connect(new InetSocketAddress(primario.host, primario.puerto), 1000);
                            System.out.println("\n*** Maestro primario volvió. Cediendo control. ***");
                            cederControl();
                            return;
                        } catch (IOException e) {
                        }
                    } catch (InterruptedException e) { return; }
                }
            }, "DetectorRegresoPrimario").start();
        }
    }

    private static void cederControl() {
        for (PrintWriter pw : clientes.keySet()) {
            pw.println("/reconectar");
        }
        clientes.clear();

        esclavosConectados.clear();

        soyMaestroActivo = false;
        System.out.println("Ahora soy ESCLAVO nuevamente.");
        iniciarHilosDeEsclavo();
    }

    // Esclavo 
    private static void iniciarHilosDeEsclavo() {
        new Thread(() -> {
            while (!soyMaestroActivo) {
                boolean conectado = conectarAlMaestroPrimario();

                if (!conectado) {
                    if (debePromoverse()) {
                        promoverAMaestro();
                        return;
                    }
                }

                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "MonitorMaestro").start();
    }

    private static boolean conectarAlMaestroPrimario() {
        ServidoresConfig.ServidorInfo primario = ServidoresConfig.getMaestroPrimario();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(primario.host, primario.puerto), 1500);
            PrintWriter pw = new PrintWriter(s.getOutputStream(), true);
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));

            pw.println("/soyEsclavo:" + miPuerto);

            ultimoHeartbeat = System.currentTimeMillis();
            System.out.println("Conectado al maestro primario. Escuchando heartbeats...");

            Thread vigilante = new Thread(() -> {
                while (!soyMaestroActivo) {
                    try {
                        Thread.sleep(1000);
                        long delta = System.currentTimeMillis() - ultimoHeartbeat;
                        if (delta > TIMEOUT_HEARTBEAT_MS) {
                            System.out.println("Timeout: sin heartbeats por " + (delta/1000) + "s");
                            try { s.close(); } catch (IOException e) {}
                            return;
                        }
                    } catch (InterruptedException e) { return; }
                }
            }, "VigilanteTimeout");
            vigilante.setDaemon(true);
            vigilante.start();

            String linea;
            while ((linea = br.readLine()) != null) {
                ultimoHeartbeat = System.currentTimeMillis();
            }
            return true;

        } catch (IOException e) {
            return false;
        }
    }

    private static boolean debePromoverse() {
        int miIndice = ServidoresConfig.getIndice(miPuerto);
        for (int i = 1; i < miIndice; i++) {
            ServidoresConfig.ServidorInfo otro = ServidoresConfig.SERVIDORES.get(i);
            try (Socket test = new Socket()) {
                test.connect(new InetSocketAddress(otro.host, otro.puerto), 800);
                System.out.println("Esclavo en puerto " + otro.puerto + " tiene prioridad. Espero.");
                return false;
            } catch (IOException e) {
            }
        }
        return true;
    }

    private static void promoverAMaestro() {
        soyMaestroActivo = true;
        System.out.println(" PROMOVIDO A MAESTRO ACTIVO");
        System.out.println(" Atendiendo clientes en puerto " + miPuerto);
        iniciarHilosDeMaestro();
    }


    public static synchronized void broadcast(String mensaje) {
        for (PrintWriter pw : clientes.keySet()) pw.println(mensaje);
    }

    public static synchronized void broadcastExcepto(String mensaje, PrintWriter excepto) {
        for (PrintWriter pw : clientes.keySet()) {
            if (pw != excepto) pw.println(mensaje);
        }
    }

    public static PrintWriter buscarCliente(String nombre) {
        for (Map.Entry<PrintWriter, String> e : clientes.entrySet()) {
            if (e.getValue().equalsIgnoreCase(nombre)) return e.getKey();
        }
        return null;
    }


    static class ManejadorConexion implements Runnable {
        private final Socket socket;
        private PrintWriter salida;
        private String nombre;
        private boolean esEsclavo = false;

        public ManejadorConexion(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try {
                BufferedReader entrada = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
                salida = new PrintWriter(socket.getOutputStream(), true);

                String primera = entrada.readLine();
                if (primera == null || primera.isBlank()) { socket.close(); return; }

                if (primera.startsWith("/soyEsclavo:")) {
                    if (!soyMaestroActivo) {
                        salida.println("/no-soy-maestro");
                        socket.close();
                        return;
                    }
                    esEsclavo = true;
                    esclavosConectados.add(salida);
                    System.out.println("Esclavo registrado: puerto " + primera.substring(12));
                    while (entrada.readLine() != null) {}
                    return;
                }

                if (!soyMaestroActivo) {
                    salida.println("Este servidor no está activo. Reconéctate.");
                    socket.close();
                    return;
                }

                nombre = primera;
                clientes.put(salida, nombre);
                broadcast(">>> " + nombre + " se unió al chat.");
                System.out.println(nombre + " conectado.");

                String mensaje;
                while ((mensaje = entrada.readLine()) != null) {
                    if (mensaje.equals("/exit")) break;
                    procesarMensaje(mensaje);
                }

            } catch (IOException e) {
                if (nombre != null) System.out.println("Conexión perdida: " + nombre);
            } finally {
                if (esEsclavo) {
                    esclavosConectados.remove(salida);
                } else if (nombre != null) {
                    clientes.remove(salida);
                    broadcast(">>> " + nombre + " salió del chat.");
                    System.out.println(nombre + " desconectado.");
                }
                try { socket.close(); } catch (IOException e) {}
            }
        }

        private void procesarMensaje(String mensaje) {
            if (mensaje.startsWith("/msg ")) {
                String resto = mensaje.substring(5).trim();
                int sp = resto.indexOf(" ");
                if (sp == -1) { salida.println("Uso: /msg <usuario> <mensaje>"); return; }
                String dest = resto.substring(0, sp);
                String txt = resto.substring(sp + 1);
                PrintWriter pwD = buscarCliente(dest);
                if (pwD == null) salida.println("Usuario '" + dest + "' no está conectado.");
                else if (pwD == salida) salida.println("No puedes enviarte mensajes a ti mismo.");
                else {
                    pwD.println("[Privado de " + nombre + "]: " + txt);
                    salida.println("[Privado para " + dest + "]: " + txt);
                    System.out.println("Privado: " + nombre + " → " + dest);
                }
                return;
            }

            if (mensaje.equals("/usuarios")) {
                StringBuilder lista = new StringBuilder("Usuarios conectados: ");
                for (String n : clientes.values()) lista.append(n).append(", ");
                salida.println(lista.toString());
                return;
            }

            if (mensaje.startsWith("/archivop:")) {
                int c1 = mensaje.indexOf(":");
                int c2 = mensaje.indexOf(":", c1 + 1);
                int c3 = mensaje.indexOf(":", c2 + 1);
                if (c3 == -1) return;
                String dest = mensaje.substring(c1 + 1, c2);
                String archivo = mensaje.substring(c2 + 1, c3);
                String b64 = mensaje.substring(c3 + 1);
                PrintWriter pwD = buscarCliente(dest);
                if (pwD == null) salida.println("Usuario '" + dest + "' no está conectado.");
                else if (pwD == salida) salida.println("No puedes enviarte archivos a ti mismo.");
                else {
                    pwD.println("/archivop:" + nombre + ":" + archivo + ":" + b64);
                    salida.println("✓ Archivo privado entregado a " + dest + ": " + archivo);
                    System.out.println("Archivo privado: " + nombre + " → " + dest);
                }
                return;
            }

            // Archivo global
            if (mensaje.startsWith("/archivo:")) {
                int c1 = mensaje.indexOf(":");
                int c2 = mensaje.indexOf(":", c1 + 1);
                if (c2 == -1) return;
                String archivo = mensaje.substring(c1 + 1, c2);
                String b64 = mensaje.substring(c2 + 1);
                broadcastExcepto("/archivo:" + nombre + ":" + archivo + ":" + b64, salida);
                System.out.println(nombre + " envió archivo: " + archivo);
                return;
            }

            // Mensaje normal
            broadcast(nombre + ": " + mensaje);
        }
    }
}