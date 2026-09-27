/* Tema 04: envio automatico de texto do clipboard por TCP.
 * Uso: java ClienteAreaTransferencia [IP_DO_SERVIDOR] [PORTA]
 * Detecta mudancas de texto por consulta periodica, nao eventos de teclado.
 */
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClienteAreaTransferencia {
    private static final int INTERVALO_MS = 500;
    private static final int RECONEXAO_MS = 2000;
    private static final int TIMEOUT_MS = 5000;
    static final int MAX_TEXTO_BYTES = 4 * 1024 * 1024;

    public static void main(String[] args) {
        try {
            if (args.length > 2) throw new IllegalArgumentException();
            String host = args.length > 0 ? args[0] : "localhost";
            int porta = args.length > 1 ? Integer.parseInt(args[1]) : 9870;
            if (porta < 1 || porta > 65535 || host.trim().isEmpty()) {
                throw new IllegalArgumentException();
            }
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            monitorarClipboard(clipboard, host, porta);
        } catch (HeadlessException | SecurityException e) {
            System.err.println("Clipboard indisponivel. Execute na sessao grafica do usuario com permissao de acesso.");
        } catch (IllegalArgumentException e) {
            System.err.println("Uso: java ClienteAreaTransferencia [IP_DO_SERVIDOR] [PORTA 1-65535]");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // null significa que o clipboard nao contem texto.
    static String lerTexto(Clipboard clipboard) throws IOException, UnsupportedFlavorException {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
        return (String) clipboard.getData(DataFlavor.stringFlavor);
    }

    static void monitorarClipboard(Clipboard clipboard, String host, int porta)
            throws InterruptedException {
        String ultimoConfirmado;
        // Registra o conteudo inicial sem envia-lo inadvertidamente ao servidor.
        while (true) {
            try {
                ultimoConfirmado = lerTexto(clipboard);
                break;
            } catch (IllegalStateException | UnsupportedFlavorException | IOException e) {
                Thread.sleep(INTERVALO_MS);
            }
        }
        System.out.println("Monitorando mudancas de texto. Conteudo inicial nao sera enviado.");
        System.out.println("Copie texto em um editor. Ctrl+C no terminal encerra o programa.");
        String ultimoRejeitado = null;
        while (!Thread.currentThread().isInterrupted()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, porta), TIMEOUT_MS);
                socket.setSoTimeout(TIMEOUT_MS);
                try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                     DataInputStream in = new DataInputStream(socket.getInputStream())) {
                    System.out.println("Conectado a " + host + ":" + porta);
                    while (!Thread.currentThread().isInterrupted()) {
                        String texto;
                        try {
                            texto = lerTexto(clipboard);
                        } catch (IllegalStateException | UnsupportedFlavorException | IOException e) {
                            // Falha de leitura local nao e uma falha de rede.
                            Thread.sleep(INTERVALO_MS);
                            continue;
                        }
                        if (texto == null) {
                            // Permite enviar A novamente depois de A -> imagem -> A.
                            ultimoConfirmado = null;
                            ultimoRejeitado = null;
                        } else if (!texto.equals(ultimoConfirmado)) {
                            byte[] dados = texto.getBytes(StandardCharsets.UTF_8);
                            if (dados.length > MAX_TEXTO_BYTES) {
                                if (!texto.equals(ultimoRejeitado)) {
                                    System.err.println("Texto ignorado: limite de 4 MiB em UTF-8 excedido.");
                                    ultimoRejeitado = texto;
                                }
                            } else {
                                ultimoRejeitado = null;
                                if (enviarTexto(out, in, dados)) {
                                    ultimoConfirmado = texto;
                                    System.out.println("Enviado ao servidor: \"" + texto + "\"");
                                    System.out.println("Servidor confirmou atualizacao do clipboard ("
                                            + dados.length + " bytes).");
                                } else {
                                    System.err.println("Clipboard do servidor ocupado. Tentando novamente em 2 segundos.");
                                    Thread.sleep(RECONEXAO_MS);
                                }
                            }
                        }
                        Thread.sleep(INTERVALO_MS);
                    }
                }
            } catch (IOException e) {
                System.err.println("Falha na conexao: " + e.getMessage()
                        + ". Reconectando em 2 segundos...");
                // Preserva a confirmacao; texto pendente sera reenviado se continuar no clipboard.
                Thread.sleep(RECONEXAO_MS);
            }
        }
    }

    // Protocolo: int de 4 bytes com tamanho + texto UTF-8; resposta 1=sucesso, 0=falha.
    static boolean enviarTexto(DataOutputStream out, DataInputStream in, byte[] dados)
            throws IOException {
        if (dados.length > MAX_TEXTO_BYTES) throw new IOException("Texto excede 4 MiB.");
        out.writeInt(dados.length);
        out.write(dados);
        out.flush();
        int resposta = in.readUnsignedByte();
        if (resposta != 0 && resposta != 1) throw new IOException("Resposta invalida do servidor.");
        return resposta == 1;
    }
}
