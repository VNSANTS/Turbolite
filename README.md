# TurboLite Beta 0.3

Aplicativo Android experimental para solicitar a parada forçada de aplicativos instalados pelo usuário, sem root, usando Shizuku com privilégios ADB.

## Interface e identidade visual
- Interface escura com identidade gamer profissional, azul profundo e ciano, com cores de destaque ciano, violeta e verde personalizáveis.
- Navegação inferior com cinco áreas: Início, Perfis, Monitor, Configurações e Jogos. A tela inicial reúne cabeçalho da marca, status do sistema e controles rápidos; as ferramentas de DPI/jogo, perfis personalizados, painel flutuante e ajustes de app/permissão ficam separados por área.
- Cartão de status mostra RAM, CPU estimada, bateria, temperatura da bateria, estado térmico do Android e velocidade total de rede; a tela Monitor atualiza a cada 5 s enquanto está aberta.
- Perfis personalizados permitem nome, emoji e seleção de funções, salvando os perfis localmente no aparelho.

## Funções atuais
- **Central de Jogo:** abre o jogo selecionado; combina modo Performance do Android, animações rápidas e preferência de taxa de tela para uma sessão competitiva; oferece também uma sessão longa com economia e modo de bateria; inclui foco Não Perturbe, com restauração manual do filtro anterior, e atalho para os indicadores flutuantes.
- **Perfis sem fechamento implícito:** streaming, eficiência e resfriamento solicitam ajustes e mantêm apps abertos. Somente o perfil HARD pede confirmação e solicita parada de outros apps de usuário.
- **Fechar outros apps:** solicita force-stop dos apps de usuário selecionados, preservando o TurboLite e o app escolhido.
- **Modo desempenho reversível:** salva os valores atuais das três escalas de animação do Android e altera para 0,5x.
- **Preferência de Hz máxima:** tenta ler os modos de tela expostos pelo Android e aplicar o maior refresh rate via configurações do sistema, guardando os valores anteriores. O XOS pode ignorar a solicitação; maior Hz consome mais bateria.
- **Restaurar configurações originais:** recupera as animações e as preferências de taxa de atualização guardadas. Não restaura apps que foram fechados.
- **Sem limpeza destrutiva:** não apaga cache, dados ou logins.

## Painel flutuante (beta experimental)
- Janela pequena arrastável por cima de outros apps.
- Alternância independente para RAM, ping, tráfego de rede, FPS, uso de CPU e temperatura. Métricas desligadas não iniciam suas medições.
- RAM usa a informação de memória do Android; tráfego de rede usa contadores do sistema; ping usa um teste para 1.1.1.1 através do Shizuku, não necessariamente o servidor do jogo.
- CPU é estimada a partir de `/proc/stat`; temperatura só aparece se o fabricante expuser uma zona térmica legível.
- FPS tenta estimar quadros recentes via `dumpsys SurfaceFlinger`; pode ficar indisponível ou variar conforme a versão do Android/XOS e o jogo em primeiro plano.
- É necessário conceder a permissão Android “Sobrepor a outros apps”. O Shizuku deve estar iniciado e autorizado para a medição de ping.

## Personalização
- Escolha uma cor de destaque ciano, violeta ou verde.
- A seleção do app a manter e a última aba aberta são salvas localmente.
- Perfis personalizados permanecem no aparelho e não exigem conta ou conexão com a nuvem.

## Limites e comportamento esperado
- As métricas de FPS/temperatura são de melhor esforço e dependem do que o firmware expõe; não são telemetria garantida.
- A Central de Jogo usa APIs e comandos padrão do Android quando disponíveis; a XOS pode rejeitar ou ignorar modo de jogo e taxas de tela.
- O foco Não Perturbe exige conceder acesso especial nas Configurações. Desative o foco no TurboLite para restaurar o filtro anterior; se o app for encerrado durante a sessão, o filtro pode permanecer ativo até ser desativado manualmente no Android.

## Limites da beta
- Solicita force-stop para apps de usuário que aparecem no launcher, exceto o TurboLite e o app escolhido na lista. Isso pode interromper reprodução, downloads, VPN e notificações; não apaga cache/dados e não usa `am kill-all`.
- Não tenta parar pacotes de sistema.
- Não limpa cache individual. Não use `pm clear`: ele apaga dados e logins.
- O modo de animação 0,5x melhora a rapidez visual das transições, mas não aumenta diretamente o FPS nem a potência do processador.
- Não ativa automaticamente o modo gamer da Infinix.
- O Android pode reiniciar apps e serviços. Fechar apps pode interromper downloads, música, VPN, alarmes de terceiros ou notificações.
- Esta é uma beta; confira o resultado no aparelho.

## Instalação
1. Instale o Shizuku pela fonte oficial: https://shizuku.rikka.app/
2. No Android 14, ative Opções do desenvolvedor e Depuração sem fio.
3. Inicie o serviço do Shizuku pela depuração sem fio. Após reiniciar o telefone, pode ser necessário iniciar novamente.
4. Abra o workflow **Build TurboLite Android Beta**, na aba Actions deste repositório, e baixe o artifact `TurboLite-Beta-APK` da execução mais recente concluída com sucesso.
5. Instale o APK, abra o TurboLite e conceda a permissão do Shizuku.
6. Selecione um jogo na lista, use a Central de Jogo para abri-lo e escolha os auxílios desejados. O fechamento de apps é uma ação separada e confirmada.

## Configuração Android
- `minSdk 26`, `targetSdk 34` e `compileSdk 35`; o alvo 34 habilita o comportamento de compatibilidade do Android 14.
- Usa Shizuku API/provider 13.1.5 com UserService para executar comandos; ações privilegiadas continuam limitadas ao que o serviço Shizuku autoriza no aparelho. Com a inicialização padrão por depuração sem fio, a identidade é shell/ADB; o TurboLite não obtém root.

## Build automático
O GitHub Actions compila o APK de depuração quando há mudanças no app e permite execução manual em Actions → Build TurboLite Android Beta → Run workflow. O arquivo `TurboLite-Beta.apk` fica disponível no artifact `TurboLite-Beta-APK` por 14 dias.


Última configuração: workflow de build automático definido em `.github/workflows/build-android.yml`.
