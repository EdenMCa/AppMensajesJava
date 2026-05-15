import java.util.*;

public class ServidoresConfig {

    public static class ServidorInfo {
        public String host;
        public int puerto;

        public ServidorInfo(String host, int puerto) {
            this.host = host;
            this.puerto = puerto;
        }
    }

    public static final List<ServidorInfo> SERVIDORES = Arrays.asList(
        new ServidorInfo("localhost", 5000),  
        new ServidorInfo("localhost", 5001), 
        new ServidorInfo("localhost", 5002) 
    );

    public static ServidorInfo getMaestroPrimario() {
        return SERVIDORES.get(0);
    }

    public static int getIndice(int puerto) {
        for (int i = 0; i < SERVIDORES.size(); i++) {
            if (SERVIDORES.get(i).puerto == puerto) return i;
        }
        return -1;
    }
}