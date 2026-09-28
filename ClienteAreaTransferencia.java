/* Tema 04: envio automatico de texto do clipboard por TCP.
 * Uso: java ClienteAreaTransferencia [IP_DO_SERVIDOR] [PORTA]
 * Detecta mudancas de texto por consulta periodica, nao eventos de teclado.
 *
 * GUIA PARA A APRESENTAÇÃO:
 * 1. main: escolhe o endereço e acessa o clipboard do cliente.
 * 2. lerTexto: verifica se o conteúdo copiado pode ser lido como texto.
 * 3. monitorarClipboard: detecta mudanças e mantém a conexão.
 * 4. enviarTexto: transmite os bytes e aguarda a confirmação do servidor.
 */
// AWT fornece acesso à área de transferência da sessão gráfica do usuário.
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
// Os streams permitem ler e escrever dados na conexão de rede.
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
// Socket representa a conexão TCP; InetSocketAddress combina endereço e porta.
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClienteAreaTransferencia {
    // CONFIGURAÇÕES: os tempos estão em milissegundos (1.000 ms = 1 segundo).
    // Consultamos o clipboard a cada meio segundo, sem ocupar a CPU em um loop contínuo.
    private static final int INTERVALO_MS = 500;
    // Espera antes de reconectar ou repetir uma atualização recusada pelo servidor.
    private static final int RECONEXAO_MS = 2000;
    // Limite para estabelecer a conexão e para aguardar a resposta em uma leitura.
    private static final int TIMEOUT_MS = 5000;
    // Limite do texto codificado: 4 MiB. Bytes e caracteres não são a mesma medida.
    static final int MAX_TEXTO_BYTES = 4 * 1024 * 1024;

    // 1. INICIALIZAÇÃO — ponto de entrada do programa.
    public static void main(String[] args) {
        try {
            // args contém o que foi digitado após o nome da classe no terminal.
            // Exemplo: java ClienteAreaTransferencia 192.168.1.50 9870
            // O IP é apenas ilustrativo.
            if (args.length > 2) throw new IllegalArgumentException();
            // Sem argumentos, conecta na própria máquina (localhost), na porta 9870.
            String host = args.length > 0 ? args[0] : "localhost";
            int porta = args.length > 1 ? Integer.parseInt(args[1]) : 9870;
            if (porta < 1 || porta > 65535 || host.trim().isEmpty()) {
                throw new IllegalArgumentException();
            }
            // Obtém o clipboard do sistema operacional; não é uma variável de texto isolada.
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            monitorarClipboard(clipboard, host, porta);
        } catch (HeadlessException | SecurityException e) {
            // Sem sessão gráfica ou permissão, não é possível acessar o clipboard.
            System.err.println("Clipboard indisponivel. Execute na sessao grafica do usuario com permissao de acesso.");
        } catch (IllegalArgumentException e) {
            // Inclui porta fora do intervalo, texto não numérico e argumentos em excesso.
            System.err.println("Uso: java ClienteAreaTransferencia [IP_DO_SERVIDOR] [PORTA 1-65535]");
        } catch (InterruptedException e) {
            // Preserva o sinal de interrupção recebido enquanto a thread estava esperando.
            Thread.currentThread().interrupt();
        }
    }

    // 2. LEITURA — DataFlavor.stringFlavor indica que há uma representação textual.
    // Retornar null informa que não há texto disponível; imagens não são transmitidas.
    // String vazia ("") é diferente de null e pode ser enviada para limpar o texto.
    static String lerTexto(Clipboard clipboard) throws IOException, UnsupportedFlavorException {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
        return (String) clipboard.getData(DataFlavor.stringFlavor);
    }

    // 3. MONITORAMENTO — consulta o conteúdo, em vez de capturar as teclas Ctrl+C.
    // Copiar pelo menu do editor também funciona. Ctrl+V não gera envio nem mensagem.
    static void monitorarClipboard(Clipboard clipboard, String host, int porta)
            throws InterruptedException {
        String ultimoConfirmado;
        // O conteúdo inicial serve de referência e não é enviado automaticamente.
        // Após iniciar, o usuário deve copiar um texto diferente para provocar o envio.
        while (true) {
            try {
                ultimoConfirmado = lerTexto(clipboard);
                break;
            } catch (IllegalStateException | UnsupportedFlavorException | IOException e) {
                // Se o clipboard estiver ocupado ou mudar durante a leitura, tenta de novo.
                Thread.sleep(INTERVALO_MS);
            }
        }
        System.out.println("Monitorando mudancas de texto. Conteudo inicial nao sera enviado.");
        System.out.println("Copie texto em um editor. Ctrl+C no terminal encerra o programa.");
        // Evita repetir o aviso de tamanho excessivo em todas as consultas.
        String ultimoRejeitado = null;
        // O loop externo permite abrir outra conexão após uma falha de rede.
        while (!Thread.currentThread().isInterrupted()) {
            // try-with-resources fecha o socket automaticamente ao sair deste bloco.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, porta), TIMEOUT_MS);
                socket.setSoTimeout(TIMEOUT_MS);
                // out envia o texto; in recebe a confirmação, pela mesma conexão TCP.
                try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                     DataInputStream in = new DataInputStream(socket.getInputStream())) {
                    System.out.println("Conectado a " + host + ":" + porta);
                    // O loop interno acompanha as mudanças enquanto a conexão é utilizada.
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
                            // Só envia se o texto for diferente do último confirmado.
                            // UTF-8 preserva acentos, emojis e quebras de linha na transmissão.
                            byte[] dados = texto.getBytes(StandardCharsets.UTF_8);
                            if (dados.length > MAX_TEXTO_BYTES) {
                                if (!texto.equals(ultimoRejeitado)) {
                                    System.err.println("Texto ignorado: limite de 4 MiB em UTF-8 excedido.");
                                    ultimoRejeitado = texto;
                                }
                            } else {
                                ultimoRejeitado = null;
                                // Não basta escrever no socket: aguardamos o servidor confirmar.
                                if (enviarTexto(out, in, dados)) {
                                    // Só então atualizamos a referência para evitar envios repetidos.
                                    ultimoConfirmado = texto;
                                    System.out.println("Enviado ao servidor: \"" + texto + "\"");
                                    System.out.println("Servidor confirmou atualizacao do clipboard ("
                                            + dados.length + " bytes).");
                                } else {
                                    // Resposta 0: o servidor recebeu, mas o clipboard estava ocupado.
                                    // Mantemos o texto sem confirmação para tentar novamente.
                                    System.err.println("Clipboard do servidor ocupado. Tentando novamente em 2 segundos.");
                                    Thread.sleep(RECONEXAO_MS);
                                }
                            }
                        }
                        // Alterações muito rápidas entre duas consultas podem passar despercebidas.
                        Thread.sleep(INTERVALO_MS);
                    }
                }
            } catch (IOException e) {
                // Falha de conexão, envio ou leitura da resposta leva à reconexão.
                // Não há fila de todas as cópias: a próxima tentativa consulta o texto atual.
                System.err.println("Falha na conexao: " + e.getMessage()
                        + ". Reconectando em 2 segundos...");
                // Preserva a confirmacao; texto pendente sera reenviado se continuar no clipboard.
                Thread.sleep(RECONEXAO_MS);
            }
        }
    }

    // 4. ENVIO — protocolo combinado entre cliente e servidor.
    // TCP transporta bytes, sem separar automaticamente uma mensagem da próxima.
    // Por isso, enviamos primeiro o tamanho e depois exatamente os bytes desse texto.
    static boolean enviarTexto(DataOutputStream out, DataInputStream in, byte[] dados)
            throws IOException {
        if (dados.length > MAX_TEXTO_BYTES) throw new IOException("Texto excede 4 MiB.");
        // Cabeçalho: inteiro de 4 bytes com a quantidade de bytes que virá a seguir.
        out.writeInt(dados.length);
        // Corpo da mensagem: o texto já convertido para UTF-8.
        out.write(dados);
        // Solicita o escoamento de eventuais dados pendentes no stream.
        // flush não é uma confirmação de que o clipboard remoto foi atualizado.
        out.flush();
        // A leitura aguarda um byte: 1 significa atualizado; 0 significa clipboard ocupado.
        int resposta = in.readUnsignedByte();
        if (resposta != 0 && resposta != 1) throw new IOException("Resposta invalida do servidor.");
        return resposta == 1;
    }
}
