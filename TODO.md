1- A mídia está tocando no home (no bluetooth, nesse exemplo) mas na navegação não está identificando.

2 - Controle de brilho não está respeitando o tema claro, e a página de navegação também não.

3 - Na seleção rápida de brilho, o valor mínimo não pode ser 0, se não a tela fica completamente apagada, definir valor mínimo como 10 ou 20

4 - Bug. Ao ligar o carro o bluetooth idêntica que está tocando música mas não reproduzir som, só começa a reproduzir se pular uma música

5 - Quando muda o tema entre claro e escuro, a navegação fica bugada e não atualiza direito, incluindo o mapa integrado. só depois que desliga e liga o carro novamente que atualiza.

6 - Incluir nas configurações se a mudança de dia e noite deve acontecer em horário específico ou quando o farol for ligado (atualmente muda com o farol, mas às vezes eu ligo o farol mesmo durante o dia e acabo precisando ajustar o brilho toda vez), e incluir também se a mudança de dia e noite pelo horario deve afetar apenas o tema ou só o brilho.

7 - A barra de indicação de volume deve servir como controle também, com botões mais e menos, e como slide.

8 - Maps, salvar endereços favoritos. O mapa não consegue indicar o número da casa corretamente

9 - A tela de aplicativos está muito lenda na multimidia, existem muitos aplicativos padrão que não consigo apagar e que atrapalham o visual. Quero que os aplicativos proprietarios do laucher fiquem no começo em ordem alfabetica, em seguida os outros aplicativos também em ordem alfabetica, quero pode ocultar aplicativos, para melhorar o visual removendo aplicativos que não uso, e talvez melhorar o carregamento da tela. Se possivel deixar a lista de aplicativos salva em um cache para deixar o load inicial mais rapido e depois de iniciar, caso tenham novos aplicativos que não estão na lista, ele atualiza.

10 - Voz integrada: a central não tem serviço de texto para fala (TTS) instalado, então as instruções por voz do mapa integrado não saem (o `map/.../route/Voice.java` usa o TextToSpeech do Android e fica mudo sem um motor). Vale para os dois provedores (OpenStreetMap e Google). Opções a avaliar:
   - instalar na central um motor de TTS livre que funciona sem internet e fala português (eSpeak NG ou RHVoice, ambos no F-Droid): nenhuma mudança no código, a suíte já usa o motor padrão do Android; conferir a qualidade da voz e se a ROM da NWD aceita trocar o motor padrão;
   - frases gravadas em áudio dentro da suíte (manobras, números e distâncias), montadas na hora: sem depender de motor, mas é preciso gravar os 8 idiomas e o pacote cresce;
   - embutir um motor na suíte: esbarra na regra de não usar bibliotecas (código nativo) e no tamanho.
   Na tela de Configurações, mostrar se há motor de TTS e o idioma dele, com um botão para testar a voz.
