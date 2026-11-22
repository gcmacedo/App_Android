# TraffIA Teste 0.8.0 (app Android)

Versão de teste do player da TraffIA para rodar num **celular Android**. Fala só com o banco **traffia-teste** (endereço e chave no `teste.properties`, na raiz).

## O que tem
- Tudo da 0.7.0: playlist sincronizada com o painel, download dos vídeos, rotação, registro de exibições, comandos do painel (sincronizar/reiniciar) e faixa com QR Code nos últimos 10 s.
- **GPS do aparelho**: posição enviada no sync e gravada em cada exibição.
- **Internet do chip**: usa a conexão do próprio celular; sem internet, guarda tudo e envia quando voltar.
- **Detecção de passageiro** (câmera frontal + ML Kit, sem internet): checa no início e perto do fim de cada **conteúdo da TraffIA**. Nenhuma imagem é salva nem enviada, só o número de rostos.
- **Corridas**: checagens positivas viram uma corrida; ela termina depois de 3 negativas seguidas; corridas com menos de 90 s são descartadas.
- **Tela de diagnóstico**: segure o canto superior esquerdo por 2 s.
- **Sem modo quiosque**: tela cheia, mas Home e Voltar funcionam e o app não abre sozinho quando o celular reinicia.

## Qual vídeo conta como "conteúdo da TraffIA"
No painel de teste, vídeo **sem anunciante cadastrado** com o nome do anunciante começando por **"TraffIA"** (ex.: `TraffIA`, `TraffIA (conteúdo diário)`), ou com a coluna `presence_check` ligada no banco.

## Como gerar e instalar
1. Android Studio → **File › Open** → esta pasta. Espere o Gradle sincronizar.
2. Celular com **depuração USB** ligada, conectado no computador.
3. Clique em **Run ▶** (ou **Build › Build APK(s)**).
4. No celular, aceite as permissões de **localização** e **câmera**.
5. A tela mostra o **código do aparelho**. Cadastre esse código no painel de teste e monte a playlist (anúncios + conteúdo TraffIA de fundo branco intercalados).

## O que vai para o banco de teste
- `play_logs`: exibição + `lat`, `lng`, `accuracy_m`, `had_passenger`, `faces`.
- `presence_checks`: cada checagem (início/fim, tem pessoa, rostos, posição).
- `rides`: corridas (início, fim, duração, pessoas, local de início e fim).
- `devices` / `device_locations`: última posição e histórico (já existiam).
