# TecnoDesk 8.0 — Portal público, orçamento automático e Pix

TecnoDesk é um projeto de assistência técnica com portal para clientes e painel administrativo no mesmo backend Java e banco de dados. Esta versão adiciona uma tabela de preços, estimativa inicial automática e geração de QR Code Pix por ordem de serviço.

## Endereços

- Portal público para clientes: `/`
- Painel privado: `/painel` (também `/admin` e `/index.html`)
- Diagnóstico da aplicação: `/api/health`

## Tabela inicial de preços

Os preços são sugestões editáveis e devem ser ajustados conforme região, experiência, complexidade e custos. O sistema cadastra os seguintes serviços públicos na primeira inicialização; serviços já existentes com o mesmo nome mantêm o preço padrão que o administrador já havia configurado.

| Serviço | Preço inicial sugerido | Faixa de referência | Unidade |
|---|---:|---:|---|
| Diagnóstico técnico | R$ 60,00 | R$ 40–80 | Por atendimento |
| Instalação e configuração de software | R$ 85,00 | R$ 50–120 | Por instalação |
| Formatação e instalação de sistema operacional | R$ 150,00 | R$ 100–200 | Por equipamento |
| Manutenção preventiva e limpeza | R$ 140,00 | R$ 100–180 | Por equipamento |
| Remoção de vírus e otimização | R$ 160,00 | R$ 120–200 | Por atendimento |
| Suporte técnico remoto | R$ 110,00 | R$ 80–150 | Por hora |
| Configuração de roteador e rede simples | R$ 220,00 | R$ 150–300 | Por atendimento |
| Manutenção de computadores para empresas | Sob orçamento | — | Sob orçamento |
| Desenvolvimento ou implantação de software | Sob orçamento | — | Sob orçamento |

Peças, materiais, licenças de software, deslocamento, urgência e contratos empresariais podem alterar o preço final. Não é uma tabela de preço universal nem garantia de mercado.

## Fluxo de atendimento e orçamento

1. O cliente acessa `/`, escolhe PF ou PJ, seleciona um serviço do catálogo, informa telefone/WhatsApp, meio de contato, modalidade e descreve o chamado.
2. O portal calcula uma estimativa inicial a partir do catálogo. Serviços por hora/equipamento/instalação multiplicam pela quantidade escolhida (limite de 20); serviços sob orçamento não exibem preço fixo.
3. Ao enviar, o sistema reutiliza um cadastro compatível ou cria um novo cadastro e uma OS com status **Solicitação recebida**. O valor estimado fica registrado na OS como ponto de partida, não como cobrança aprovada.
4. No painel, abra **Ordens de serviço**, revise a solicitação e ajuste o escopo, preço e prazo. Para aprovar o valor e liberar a cobrança, altere o status para **Aguardando pagamento** e salve a OS.
5. Na área **Financeiro → Configuração Pix**, cadastre a chave Pix, o nome do recebedor e a cidade. Em **Gerar QR Code Pix**, selecione uma OS com status `Aguardando pagamento` e valor positivo. O TecnoDesk gera um QR Code, um Pix Copia e Cola e permite baixar a imagem.
6. Depois de conferir o crédito real no aplicativo do Itaú, use **Confirmar recebimento no Itaú**. O sistema registra o pagamento como pago e cria uma entrada no Financeiro.

## Limitação de pagamentos (importante)

Esta versão monta um código Pix com chave de recebimento e valor aprovados e gera uma imagem QR. **Ela não consulta sua conta Itaú e não confirma o pagamento automaticamente.** A confirmação manual deve ser feita apenas depois de você conferir o crédito efetivo no banco. QR Code ou comprovante enviado pelo cliente não provam, por si só, que o dinheiro chegou.

A integração bancária automática depende de acesso a API de cobrança/consulta Pix do Itaú ou de um provedor de pagamentos compatível, com credenciais oficiais e confirmação por notificação confiável. O acesso deve ser confirmado com o banco; nenhuma senha bancária deve ser colocada no TecnoDesk.

## Rodar localmente no VS Code (Windows / PowerShell)

Requisitos: JDK 17 ou superior e Apache Maven.

```powershell
mvn clean package
java -jar target/cadastro-clientes-8.0.0.jar
```

Abra `http://localhost:8080` para o portal e `http://localhost:8080/painel` para o painel.

Na primeira execução local, se não existir nenhum usuário, o sistema cria `admin` / `Admin@12345!`. **Essas credenciais são somente para desenvolvimento local.** Antes de disponibilizar o painel na internet, configure usuário/senha fortes por meio das variáveis de ambiente, e não utilize a senha padrão.

## Configuração Pix

1. Entre no painel e abra **Financeiro**.
2. Em **Configuração Pix**, informe sua chave de recebimento Itaú, o nome do titular conforme o banco e a cidade do recebedor (normalizada para o formato solicitado pelo padrão Pix).
3. Salve. A chave fica guardada na base de dados do TecnoDesk, em uma rota administrativa protegida, e não é mostrada ao público.
4. Revise e aprove a OS antes de gerar a cobrança. Verifique o nome do recebedor no aplicativo do pagador.

Use uma base local protegida e nunca versionada para teste. A chave Pix é dado de recebimento; não coloque senha bancária, token, certificado ou credenciais de API no código, no ZIP ou no GitHub.

## Hospedagem

O projeto inclui `Dockerfile` e `render.yaml`. Para publicação, configure PostgreSQL persistente no Render ou outro provedor e defina `DATABASE_URL`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `APP_ENV=production` e `COOKIE_SECURE=true`. Não publique o banco SQLite local. Configure HTTPS e backups.

## Proteções e privacidade

- Rotas administrativas usam sessão autenticada e token CSRF.
- O formulário público tem validação de campos, token CSRF, campo honeypot e limite simples por IP em memória. Para uso público maior, complemente com CAPTCHA/WAF e rate limit compartilhado.
- Solicitações guardam dados de contato necessários para responder ao chamado. Não peça senhas de computador, códigos de autenticação nem dados de cartão no formulário.
- O botão WhatsApp no painel abre a conversa a partir do telefone cadastrado, quando há um número com DDD.
- Mantenha dados pessoais e configurações fora do GitHub e limite o acesso ao painel.

## Teste e validação

A sintaxe do Java foi verificada com `javac` e stubs temporários apenas para análise de sintaxe; JavaScript, HTML básico, XML Maven e estrutura do ZIP também devem ser conferidos antes de publicar. A compilação completa do Maven precisa ser executada localmente para que as dependências reais sejam baixadas.
