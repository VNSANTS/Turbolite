# TurboLite Beta 0.1

Aplicativo Android experimental para solicitar a parada forçada de aplicativos instalados pelo usuário, sem root, usando Shizuku com privilégios ADB.

## Limites da beta
- Fecha apps de usuário que aparecem no launcher, exceto o TurboLite e o app escolhido na lista.
- Não tenta parar pacotes de sistema.
- Não limpa cache individual. Não use `pm clear`: ele apaga dados e logins.
- Não ativa automaticamente o modo gamer da Infinix.
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
