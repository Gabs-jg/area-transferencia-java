/* Tema 04: recebe texto via TCP e atualiza o clipboard do servidor.
 * Uso: java ServidorAreaTransferencia [PORTA]
 * Protocolo: tamanho (int) + bytes UTF-8; resposta 1=sucesso, 0=clipboard ocupado.
 * Destinado a demonstracoes em rede confiavel; nao utiliza autenticacao ou TLS.
 *
 * GUIA PARA A APRESENTAÇÃO:
 * 1. main: abre a porta e aceita as conexões.
 * 2. ClienteHandler.run: recebe cada mensagem e devolve uma confirmação.
 * 3. decodificar: transforma os bytes UTF-8 em texto.
 * 4. atualizarAreaDeTransferencia: permite colar o texto neste computador.
 */
// AWT acessa o clipboard; StringSelection representa o texto que será colocado nele.
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
// Streams fazem a leitura e a escrita de dados pela conexão TCP.
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
// ServerSocket aguarda conexões; Socket representa a conexão com um cliente.
import java.net.ServerSocket;
import java.net.Socket;
// Estas classes decodificam UTF-8 e permitem rejeitar sequências inválidas.
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
// O semáforo controla quantos clientes podem ser atendidos ao mesmo tempo.
import java.util.concurrent.Semaphore;

public class ServidorAreaTransferencia {
    // 1. INICIALIZAÇÃO — o servidor deve estar rodando antes da conexão do cliente.
    public static void main(String[] args) {
        try {
            // A porta pode ser informada no terminal. Sem argumento, usamos 9870.
            // Exemplo: java ServidorAreaTransferencia 9870
            if (args.length > 1) throw new IllegalArgumentException();
            int porta = args.length > 0 ? Integer.parseInt(args[0]) : 9870;
            if (porta < 1 || porta > 65535) throw new IllegalArgumentException();
            // Falha antes de abrir a porta se nao houver clipboard acessivel.
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            // Cada cliente ocupa uma das oito vagas até seu atendimento terminar.
            Semaphore vagas = new Semaphore(8);
            // Abre uma porta TCP de escuta, acessível conforme a rede e o firewall.
            // try-with-resources fecha o ServerSocket quando este bloco termina.
            try (ServerSocket servidor = new ServerSocket(porta)) {
                System.out.println("Servidor aguardando conexoes na porta " + porta + ".");
                System.out.println("Use somente em rede confiavel. Ctrl+C encerra o programa.");
                while (!Thread.currentThread().isInterrupted()) {
                    // accept fica aguardando e retorna quando chega uma nova conexão.
                    Socket cliente = servidor.accept();
                    // tryAcquire tenta reservar uma vaga sem esperar que outra seja liberada.
                    if (!vagas.tryAcquire()) {
                        cliente.close();
                        System.err.println("Limite de 8 clientes atingido.");
                        continue;
                    }
                    // Uma thread atende este cliente; o loop principal pode aceitar outros.
                    new Thread(() -> {
                        try {
                            new ClienteHandler(cliente, clipboard).run();
                        } finally {
                            // Libera a vaga mesmo se o atendimento terminar por erro.
                            vagas.release();
                        }
                    }, "cliente-clipboard").start();
                }
            }
        } catch (HeadlessException | SecurityException e) {
            // O clipboard exige sessão gráfica e permissão de acesso.
            System.err.println("Clipboard indisponivel. Execute na sessao grafica do usuario com permissao de acesso.");
        } catch (IllegalArgumentException e) {
            System.err.println("Uso: java ServidorAreaTransferencia [PORTA 1-65535]");
        } catch (IOException e) {
            // Inclui falhas ao abrir a porta, como outra instância já estar usando-a.
            System.err.println("Erro no servidor: " + e.getMessage());
        }
    }
}

// Cada instância atende uma conexão. Runnable fornece o método run usado pela thread.
class ClienteHandler implements Runnable {
    // Deve corresponder ao limite aceito pelo cliente: 4 MiB de texto em UTF-8.
    static final int MAX_TEXTO_BYTES = 4 * 1024 * 1024;
    // static compartilha a mesma trava entre todos os atendimentos deste servidor.
    private static final Object TRAVA_ATUALIZACAO = new Object();
    private final Socket socket;
    private final Clipboard clipboard;

    ClienteHandler(Socket socket, Clipboard clipboard) {
        // Cada cliente tem seu socket, mas todos usam o mesmo clipboard do servidor.
        this.socket = socket;
        this.clipboard = clipboard;
    }

    @Override
    public void run() {
        // 2. RECEBIMENTO — fecha conexão e streams ao terminar, inclusive em caso de erro.
        try (Socket conexao = socket;
             DataInputStream in = new DataInputStream(conexao.getInputStream());
             DataOutputStream out = new DataOutputStream(conexao.getOutputStream())) {
            System.out.println("Cliente conectado: " + conexao.getInetAddress());
            while (!Thread.currentThread().isInterrupted()) {
                // O cliente envia primeiro um inteiro com o tamanho do texto em bytes.
                int tamanho = in.readInt();
                // Valida antes de reservar memória; não aceita tamanho negativo ou excessivo.
                if (tamanho < 0 || tamanho > MAX_TEXTO_BYTES) {
                    throw new IOException("Tamanho de mensagem invalido: " + tamanho);
                }
                byte[] dados = new byte[tamanho];
                // Aguarda todos os bytes, mesmo que TCP divida o texto em pacotes.
                in.readFully(dados);
                String texto = decodificar(dados);
                // Receber os bytes não basta: precisamos atualizar o clipboard real.
                boolean atualizado = atualizarAreaDeTransferencia(texto);
                // Responde somente depois da tentativa de atualização: 1=sucesso, 0=ocupado.
                out.writeByte(atualizado ? 1 : 0);
                out.flush();
                if (atualizado) {
                    // O terminal mantém o histórico. O clipboard contém o último texto atualizado.
                    System.out.println("Recebido do cliente: \"" + texto + "\"");
                    System.out.println("Clipboard atualizado (" + tamanho + " bytes) por "
                            + conexao.getInetAddress());
                } else {
                    System.err.println("Clipboard ocupado; cliente informado para tentar novamente.");
                }
            }
        } catch (EOFException e) {
            // O fluxo acabou: o cliente fechou a conexão ou faltaram bytes da mensagem.
            System.out.println("Cliente desconectou ou enviou mensagem incompleta.");
        } catch (IOException | SecurityException e) {
            System.err.println("Falha ao atender cliente: " + e.getMessage());
        } catch (InterruptedException e) {
            // Preserva o sinal de interrupção se a thread estava aguardando uma nova tentativa.
            Thread.currentThread().interrupt();
        }
    }

    // 3. DECODIFICAÇÃO — usa a mesma codificação UTF-8 do cliente.
    // REPORT faz dados inválidos causarem uma exceção, em vez de substituir caracteres.
    private static String decodificar(byte[] dados) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(dados)).toString();
    }

    // 4. ATUALIZAÇÃO — torna o texto disponível para Ctrl+V em um editor do servidor.
    private boolean atualizarAreaDeTransferencia(String texto) throws InterruptedException {
        // Apenas uma thread por vez entra neste bloco; a última atualização prevalece.
        // Isso não impede que outros aplicativos alterem o clipboard depois.
        synchronized (TRAVA_ATUALIZACAO) {
            // Tenta até cinco vezes, pois outro aplicativo pode estar usando o clipboard.
            for (int tentativa = 0; tentativa < 5; tentativa++) {
                try {
                    // StringSelection adapta a String ao formato aceito pelo clipboard.
                    // null indica que não registramos um objeto para receber avisos de posse.
                    clipboard.setContents(new StringSelection(texto), null);
                    return true;
                } catch (IllegalStateException e) {
                    // Aguarda 100 ms antes de tentar novamente, exceto após a última tentativa.
                    if (tentativa < 4) Thread.sleep(100);
                }
            }
            // Todas as tentativas falharam. run enviará 0 para o cliente tentar mais tarde.
            return false;
        }
    }
}
