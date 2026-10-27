# TurboLite Beta 0.1

Aplicativo Android experimental para solicitar a parada forçada de aplicativos instalados pelo usuário, sem root, usando Shizuku com privilégios ADB.

## Funções atuais
- **Fechar outros apps:** solicita force-stop dos apps de usuário selecionados, preservando o TurboLite e o app escolhido.
- **Modo desempenho reversível:** salva os valores atuais das três escalas de animação do Android e altera para 0,5x.
- **Restaurar configurações originais:** recupera os valores salvos das animações. Não restaura apps que foram fechados.
- **Sem limpeza destrutiva:** não apaga cache, dados ou logins.

## Painel flutuante (beta experimental)
- Janela pequena arrastável por cima de outros apps.
- Alternância independente para RAM, ping e FPS.
- RAM usa a informação de memória disponível do Android; ping usa um teste ICMP para 1.1.1.1 através do Shizuku, não necessariamente o servidor do jogo.
- **FPS ainda não está medido nesta versão:** o painel mostra `FPS: -- (não medido)` até validarmos uma leitura real via SurfaceFlinger no XOS deste aparelho.
- É necessário conceder a permissão Android “Sobrepor a outros apps”. O Shizuku deve estar iniciado e autorizado para a medição de ping.

## Próximas funções possíveis
1. Perfis salvos: Normal, Jogo e Economia, sempre com opção de voltar.
2. Lista de exclusão para apps que não devem ser fechados (mensagens, música, VPN, relógio).
3. Prévia/relatório dos apps que serão fechados antes de confirmar.
4. Indicadores de bateria, RAM disponível e armazenamento, apenas leitura.
5. Atalhos para abrir as configurações nativas de bateria, tela e modo jogo da Infinix.
6. Histórico das ações e botão para repetir a última configuração.
7. Teste de desempenho antes/depois, sem prometer ganho de FPS.
8. Ajuste de taxa de atualização somente se o aparelho e a versão do Android permitirem, guardando o valor original.

## Limites da beta
- Fecha apps de usuário que aparecem no launcher, exceto o TurboLite e o app escolhido na lista.
- Não tenta parar pacotes de sistema.
- Não limpa cache individual. Não use `pm clear`: ele apaga dados e logins.
- O modo de animação 0,5x melhora a rapidez visual das transições, mas não aumenta diretamente o FPS nem a potência do processador.\n- Não ativa automaticamente o modo gamer da Infinix.
- O Android pode reiniciar apps e serviços. Fechar apps pode interromper downloads, música, VPN, alarmes de terceiros ou notificações.
- Esta é uma beta; confira o resultado no aparelho.

## Instalação
1. Instale o Shizuku pela fonte oficial: https://shizuku.rikka.app/
2. No Android 14, ative Opções do desenvolvedor e Depuração sem fio.
3. Inicie o serviço do Shizuku pela depuração sem fio. Após reiniciar o telefone, pode ser necessário iniciar novamente.
4. Baixe o APK no workflow **Build TurboLite Android Beta**, na aba Actions deste repositório, na seção Artifacts.
5. Instale o APK, abra o TurboLite e conceda a permissão do Shizuku.
6. Selecione o jogo/app que quer manter e toque em **FECHAR OS OUTROS APPS**.

## Build automático
O GitHub Actions compila o APK de depuração em cada push e também permite execução manual em Actions → Build TurboLite Android Beta → Run workflow. O arquivo gerado é `app/build/outputs/apk/debug/app-debug.apk` e fica disponível como artifact por 14 dias.


Última configuração: workflow de build automático definido em `.github/workflows/build-android.yml`.
