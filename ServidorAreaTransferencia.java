/* Tema 04: recebe texto via TCP e atualiza o clipboard do servidor.
 * Uso: java ServidorAreaTransferencia [PORTA]
 * Protocolo: tamanho (int) + bytes UTF-8; resposta 1=sucesso, 0=clipboard ocupado.
 * Destinado a demonstracoes em rede confiavel; nao utiliza autenticacao ou TLS.
 */
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

public class ServidorAreaTransferencia {
    public static void main(String[] args) {
        try {
            if (args.length > 1) throw new IllegalArgumentException();
            int porta = args.length > 0 ? Integer.parseInt(args[0]) : 9870;
            if (porta < 1 || porta > 65535) throw new IllegalArgumentException();
            // Falha antes de abrir a porta se nao houver clipboard acessivel.
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Semaphore vagas = new Semaphore(8);
            try (ServerSocket servidor = new ServerSocket(porta)) {
                System.out.println("Servidor aguardando conexoes na porta " + porta + ".");
                System.out.println("Use somente em rede confiavel. Ctrl+C encerra o programa.");
                while (!Thread.currentThread().isInterrupted()) {
                    Socket cliente = servidor.accept();
                    if (!vagas.tryAcquire()) {
                        cliente.close();
                        System.err.println("Limite de 8 clientes atingido.");
                        continue;
                    }
                    new Thread(() -> {
                        try {
                            new ClienteHandler(cliente, clipboard).run();
                        } finally {
                            vagas.release();
                        }
                    }, "cliente-clipboard").start();
                }
            }
        } catch (HeadlessException | SecurityException e) {
            System.err.println("Clipboard indisponivel. Execute na sessao grafica do usuario com permissao de acesso.");
        } catch (IllegalArgumentException e) {
            System.err.println("Uso: java ServidorAreaTransferencia [PORTA 1-65535]");
        } catch (IOException e) {
            System.err.println("Erro no servidor: " + e.getMessage());
        }
    }
}

class ClienteHandler implements Runnable {
    static final int MAX_TEXTO_BYTES = 4 * 1024 * 1024;
    private static final Object TRAVA_ATUALIZACAO = new Object();
    private final Socket socket;
    private final Clipboard clipboard;

    ClienteHandler(Socket socket, Clipboard clipboard) {
        this.socket = socket;
        this.clipboard = clipboard;
    }

    @Override
    public void run() {
        try (Socket conexao = socket;
             DataInputStream in = new DataInputStream(conexao.getInputStream());
             DataOutputStream out = new DataOutputStream(conexao.getOutputStream())) {
            System.out.println("Cliente conectado: " + conexao.getInetAddress());
            while (!Thread.currentThread().isInterrupted()) {
                int tamanho = in.readInt();
                if (tamanho < 0 || tamanho > MAX_TEXTO_BYTES) {
                    throw new IOException("Tamanho de mensagem invalido: " + tamanho);
                }
                byte[] dados = new byte[tamanho];
                // Aguarda todos os bytes, mesmo que TCP divida o texto em pacotes.
                in.readFully(dados);
                String texto = decodificar(dados);
                boolean atualizado = atualizarAreaDeTransferencia(texto);
                out.writeByte(atualizado ? 1 : 0);
                out.flush();
                if (atualizado) {
                    System.out.println("Recebido do cliente: \"" + texto + "\"");
                    System.out.println("Clipboard atualizado (" + tamanho + " bytes) por "
                            + conexao.getInetAddress());
                } else {
                    System.err.println("Clipboard ocupado; cliente informado para tentar novamente.");
                }
            }
        } catch (EOFException e) {
            System.out.println("Cliente desconectou ou enviou mensagem incompleta.");
        } catch (IOException | SecurityException e) {
            System.err.println("Falha ao atender cliente: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String decodificar(byte[] dados) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(dados)).toString();
    }

    private boolean atualizarAreaDeTransferencia(String texto) throws InterruptedException {
        // Serializa escritas de clientes distintos; a ultima atualizacao prevalece.
        synchronized (TRAVA_ATUALIZACAO) {
            for (int tentativa = 0; tentativa < 5; tentativa++) {
                try {
                    clipboard.setContents(new StringSelection(texto), null);
                    return true;
                } catch (IllegalStateException e) {
                    if (tentativa < 4) Thread.sleep(100);
                }
            }
            return false;
        }
    }
}
