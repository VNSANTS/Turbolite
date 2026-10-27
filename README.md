# TurboLite Beta 0.2

Aplicativo Android experimental para solicitar a parada forçada de aplicativos instalados pelo usuário, sem root, usando Shizuku com privilégios ADB.

## Interface e identidade visual
- Interface escura com identidade gamer profissional, azul profundo e ciano, inspirada na opção 3 aprovada para a marca.
- Navegação inferior com cinco áreas: Início, Perfis, Monitor, Configurações e Jogos. A tela inicial reúne cabeçalho da marca, status do sistema e controles rápidos; as ferramentas de DPI/jogo, perfis personalizados, painel flutuante e ajustes de app/permissão ficam separados por área.
- Cartão de status mostra RAM, CPU estimada, bateria e temperatura da bateria quando disponível; é atualizado manualmente pelo botão.
- Perfis personalizados permitem nome, emoji e seleção de funções, salvando os perfis localmente no aparelho.

## Funções atuais
- **Fechar outros apps:** solicita force-stop dos apps de usuário selecionados, preservando o TurboLite e o app escolhido.
- **Modo desempenho reversível:** salva os valores atuais das três escalas de animação do Android e altera para 0,5x.
- **Preferência de Hz máxima:** tenta ler os modos de tela expostos pelo Android e aplicar o maior refresh rate via configurações do sistema, guardando os valores anteriores. O XOS pode ignorar a solicitação; maior Hz consome mais bateria.
- **Restaurar configurações originais:** recupera as animações e as preferências de taxa de atualização guardadas. Não restaura apps que foram fechados.
- **Sem limpeza destrutiva:** não apaga cache, dados ou logins.

## Painel flutuante (beta experimental)
- Janela pequena arrastável por cima de outros apps.
- Alternância independente para RAM, ping, FPS, uso de CPU e temperatura.
- RAM usa a informação de memória do Android; ping usa um teste para 1.1.1.1 através do Shizuku, não necessariamente o servidor do jogo.
- CPU é estimada a partir de `/proc/stat`; temperatura só aparece se o fabricante expuser uma zona térmica legível.
- FPS tenta estimar quadros recentes via `dumpsys SurfaceFlinger`; pode ficar indisponível ou variar conforme a versão do Android/XOS e o jogo em primeiro plano.
- É necessário conceder a permissão Android “Sobrepor a outros apps”. O Shizuku deve estar iniciado e autorizado para a medição de ping.

## Limites e comportamento esperado
- As métricas de FPS/temperatura são de melhor esforço e dependem do que o firmware expõe; não são telemetria garantida.

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
4. Abra o workflow **Build TurboLite Android Beta**, na aba Actions deste repositório, e baixe o artifact `TurboLite-beta-debug-apk` da execução mais recente concluída com sucesso.
5. Instale o APK, abra o TurboLite e conceda a permissão do Shizuku.
6. Selecione o jogo/app que quer manter e toque em **FECHAR OS OUTROS APPS**.

## Build automático
O GitHub Actions compila o APK de depuração quando há mudanças no app e permite execução manual em Actions → Build TurboLite Android Beta → Run workflow. O arquivo `app-debug.apk` fica disponível no artifact `TurboLite-beta-debug-apk` por 14 dias.


Última configuração: workflow de build automático definido em `.github/workflows/build-android.yml`.
