package br.com.cadastro;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Cadastro de clientes com sessão protegida e banco SQLite local/PostgreSQL na nuvem. */
public class App {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long SESSION_TTL_MS = 8L * 60 * 60 * 1000;
    private static final long LOGIN_WINDOW_MS = 15L * 60 * 1000;
    private static final int MAX_LOGIN_FAILURES = 8;
    private static final long PUBLIC_REQUEST_WINDOW_MS = 15L * 60 * 1000;
    private static final int MAX_PUBLIC_REQUESTS_PER_WINDOW = 5;
    private static final DatabaseConfig DATABASE = DatabaseConfig.fromEnvironment();
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LoginAttempt> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PublicRequestAttempt> PUBLIC_REQUEST_ATTEMPTS = new ConcurrentHashMap<>();
    private static final String DUMMY_PASSWORD_HASH = hashPassword("not-a-real-account-password");

    public static void main(String[] args) throws IOException {
        try {
            if (!DATABASE.postgres) {
                Files.createDirectories(DATABASE.sqlitePath.getParent());
                Class.forName("org.sqlite.JDBC");
            } else {
                Class.forName("org.postgresql.Driver");
            }
            initializeDatabase();
            ensureInitialAdmin();
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Não foi possível inicializar o banco de dados. Confira as variáveis de ambiente e as dependências.", e);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/", App::handleRequest);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("\n========================================");
        System.out.println("  TecnoDesk Portal + Painel está no ar!");
        System.out.println("  Porta: " + PORT);
        System.out.println("  Banco: " + (DATABASE.postgres ? "PostgreSQL remoto" : "SQLite local"));
        System.out.println("  Login obrigatório habilitado.");
        System.out.println("========================================\n");
    }

    private static Connection connect() throws SQLException {
        if (DATABASE.postgres && DATABASE.username != null && DATABASE.password != null) {
            return DriverManager.getConnection(DATABASE.jdbcUrl, DATABASE.username, DATABASE.password);
        }
        return DriverManager.getConnection(DATABASE.jdbcUrl);
    }

    private static void initializeDatabase() throws SQLException {
        String idType = DATABASE.postgres ? "BIGSERIAL PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String textType = DATABASE.postgres ? "VARCHAR(255)" : "TEXT";
        String customerSql = "CREATE TABLE IF NOT EXISTS customers ("
                + "id " + idType + ","
                + "name VARCHAR(120) NOT NULL,"
                + "email VARCHAR(160) NOT NULL DEFAULT '',"
                + "phone VARCHAR(40) NOT NULL DEFAULT '',"
                + "cpf VARCHAR(18) NOT NULL DEFAULT '',"
                + "birth_date VARCHAR(10) NOT NULL DEFAULT '',"
                + "company VARCHAR(120) NOT NULL DEFAULT '',"
                + "status VARCHAR(20) NOT NULL DEFAULT 'Ativo',"
                + "address VARCHAR(180) NOT NULL DEFAULT '',"
                + "neighborhood VARCHAR(100) NOT NULL DEFAULT '',"
                + "city VARCHAR(100) NOT NULL DEFAULT '',"
                + "state VARCHAR(2) NOT NULL DEFAULT '',"
                + "zip_code VARCHAR(12) NOT NULL DEFAULT '',"
                + "notes TEXT NOT NULL DEFAULT '',"
                + "person_type VARCHAR(2) NOT NULL DEFAULT 'PF',"
                + "record_type VARCHAR(40) NOT NULL DEFAULT 'Cliente avulso',"
                + "trade_name VARCHAR(160) NOT NULL DEFAULT '',"
                + "contact_name VARCHAR(120) NOT NULL DEFAULT '',"
                + "service_type VARCHAR(80) NOT NULL DEFAULT 'Assistência técnica / manutenção',"
                + "legacy_company_id BIGINT,"
                + "created_at VARCHAR(32) NOT NULL,"
                + "updated_at VARCHAR(32) NOT NULL)";
        String userSql = "CREATE TABLE IF NOT EXISTS app_users ("
                + "id " + idType + ","
                + "username VARCHAR(100) NOT NULL UNIQUE,"
                + "password_hash " + textType + " NOT NULL,"
                + "created_at VARCHAR(32) NOT NULL)";
        String companiesSql = "CREATE TABLE IF NOT EXISTS companies ("
                + "id " + idType + ","
                + "name VARCHAR(160) NOT NULL, trade_name VARCHAR(160) NOT NULL DEFAULT '',"
                + "cnpj VARCHAR(24) NOT NULL DEFAULT '', email VARCHAR(160) NOT NULL DEFAULT '',"
                + "phone VARCHAR(40) NOT NULL DEFAULT '', contact_name VARCHAR(120) NOT NULL DEFAULT '',"
                + "address VARCHAR(180) NOT NULL DEFAULT '', city VARCHAR(100) NOT NULL DEFAULT '', state VARCHAR(2) NOT NULL DEFAULT '',"
                + "status VARCHAR(20) NOT NULL DEFAULT 'Ativa', notes TEXT NOT NULL DEFAULT '',"
                + "created_at VARCHAR(32) NOT NULL, updated_at VARCHAR(32) NOT NULL)";
        String servicesSql = "CREATE TABLE IF NOT EXISTS service_catalog ("
                + "id " + idType + ", name VARCHAR(140) NOT NULL, category VARCHAR(100) NOT NULL DEFAULT '',"
                + "description TEXT NOT NULL DEFAULT '', default_price NUMERIC(12,2) NOT NULL DEFAULT 0,"
                + "estimated_hours NUMERIC(8,2) NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'Ativo',"
                + "public_min_price NUMERIC(12,2) NOT NULL DEFAULT 0, public_max_price NUMERIC(12,2) NOT NULL DEFAULT 0,"
                + "pricing_unit VARCHAR(40) NOT NULL DEFAULT 'por atendimento', public_enabled SMALLINT NOT NULL DEFAULT 0,"
                + "created_at VARCHAR(32) NOT NULL, updated_at VARCHAR(32) NOT NULL)";
        String ordersSql = "CREATE TABLE IF NOT EXISTS service_orders ("
                + "id " + idType + ", company_id BIGINT, customer_id BIGINT, service_id BIGINT,"
                + "title VARCHAR(160) NOT NULL, service_type VARCHAR(80) NOT NULL DEFAULT 'Assistência técnica / manutenção', description TEXT NOT NULL DEFAULT '',"
                + "status VARCHAR(30) NOT NULL DEFAULT 'Aberta', priority VARCHAR(20) NOT NULL DEFAULT 'Normal',"
                + "technician VARCHAR(120) NOT NULL DEFAULT '', scheduled_date VARCHAR(20) NOT NULL DEFAULT '',"
                + "completed_date VARCHAR(20) NOT NULL DEFAULT '', price NUMERIC(12,2) NOT NULL DEFAULT 0,"
                + "notes TEXT NOT NULL DEFAULT '', created_at VARCHAR(32) NOT NULL, updated_at VARCHAR(32) NOT NULL)";
        String operationalRecordsSql = "CREATE TABLE IF NOT EXISTS operational_records ("
                + "id " + idType + ", module VARCHAR(40) NOT NULL, payload TEXT NOT NULL,"
                + "created_at VARCHAR(32) NOT NULL, updated_at VARCHAR(32) NOT NULL)";
        String appSettingsSql = "CREATE TABLE IF NOT EXISTS app_settings (setting_key VARCHAR(100) PRIMARY KEY, setting_value TEXT NOT NULL, updated_at VARCHAR(32) NOT NULL)";
        String pixPaymentsSql = "CREATE TABLE IF NOT EXISTS pix_payments (order_id BIGINT PRIMARY KEY, amount NUMERIC(12,2) NOT NULL, txid VARCHAR(25) NOT NULL, payload TEXT NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'Pendente', created_at VARCHAR(32) NOT NULL, paid_at VARCHAR(32) NOT NULL DEFAULT '')";
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(customerSql);
            statement.execute(userSql);
            statement.execute(companiesSql);
            statement.execute(servicesSql);
            statement.execute(ordersSql);
            statement.execute(operationalRecordsSql);
            statement.execute(appSettingsSql);
            statement.execute(pixPaymentsSql);
        }
        // Migração automática: preserva cadastros feitos nas versões anteriores.
        try (Connection connection = connect()) {
            ensureColumn(connection, "cpf", "VARCHAR(18) NOT NULL DEFAULT ''");
            ensureColumn(connection, "birth_date", "VARCHAR(10) NOT NULL DEFAULT ''");
            ensureColumn(connection, "company", "VARCHAR(120) NOT NULL DEFAULT ''");
            ensureColumn(connection, "status", "VARCHAR(20) NOT NULL DEFAULT 'Ativo'");
            ensureColumn(connection, "address", "VARCHAR(180) NOT NULL DEFAULT ''");
            ensureColumn(connection, "neighborhood", "VARCHAR(100) NOT NULL DEFAULT ''");
            ensureColumn(connection, "state", "VARCHAR(2) NOT NULL DEFAULT ''");
            ensureColumn(connection, "zip_code", "VARCHAR(12) NOT NULL DEFAULT ''");
            ensureColumn(connection, "notes", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(connection, "person_type", "VARCHAR(2) NOT NULL DEFAULT 'PF'");
            ensureColumn(connection, "record_type", "VARCHAR(40) NOT NULL DEFAULT 'Cliente avulso'");
            ensureColumn(connection, "trade_name", "VARCHAR(160) NOT NULL DEFAULT ''");
            ensureColumn(connection, "contact_name", "VARCHAR(120) NOT NULL DEFAULT ''");
            ensureColumn(connection, "service_type", "VARCHAR(80) NOT NULL DEFAULT 'Assistência técnica / manutenção'");
            ensureColumn(connection, "legacy_company_id", "BIGINT");
            ensureTableColumn(connection, "service_orders", "service_type", "VARCHAR(80) NOT NULL DEFAULT 'Assistência técnica / manutenção'");
            ensureTableColumn(connection, "service_catalog", "public_min_price", "NUMERIC(12,2) NOT NULL DEFAULT 0");
            ensureTableColumn(connection, "service_catalog", "public_max_price", "NUMERIC(12,2) NOT NULL DEFAULT 0");
            ensureTableColumn(connection, "service_catalog", "pricing_unit", "VARCHAR(40) NOT NULL DEFAULT 'por atendimento'");
            ensureTableColumn(connection, "service_catalog", "public_enabled", "SMALLINT NOT NULL DEFAULT 0");
            // Migra empresas cadastradas nas versões anteriores para a base unificada, sem duplicá-las.
            try (Statement migration = connection.createStatement()) {
                migration.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_customers_legacy_company ON customers(legacy_company_id)");
                migration.execute("INSERT INTO customers(name,email,phone,cpf,birth_date,company,status,address,neighborhood,city,state,zip_code,notes,person_type,record_type,trade_name,contact_name,service_type,legacy_company_id,created_at,updated_at) " +
                        "SELECT co.name,co.email,co.phone,co.cnpj,'',co.trade_name,CASE WHEN co.status='Inativa' THEN 'Inativo' WHEN co.status='Pendente' THEN 'Pendente' ELSE 'Ativo' END,co.address,'',co.city,co.state,'',co.notes,'PJ','Empresa contratante',co.trade_name,co.contact_name,'Assistência técnica / manutenção',co.id,co.created_at,co.updated_at FROM companies co WHERE NOT EXISTS (SELECT 1 FROM customers cu WHERE cu.legacy_company_id=co.id)");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX IF NOT EXISTS idx_customers_name ON customers(name)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_customers_email ON customers(email)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_customers_status ON customers(status)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_companies_name ON companies(name)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_service_catalog_name ON service_catalog(name)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_service_orders_status ON service_orders(status)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_service_orders_company ON service_orders(company_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_operational_records_module ON operational_records(module, id)");
            }
        }
        seedDefaultPublicServices();
    }

    private static void seedDefaultPublicServices() throws SQLException {
        record Seed(String name, String category, String description, String price, String min, String max, String unit, String hours) { }
        List<Seed> seeds = List.of(
                new Seed("Diagnóstico técnico", "Assistência técnica / manutenção", "Avaliação inicial do problema. Faixa de referência: R$ 40,00 a R$ 80,00. Peças e deslocamento à parte.", "60.00", "40.00", "80.00", "por atendimento", "1.00"),
                new Seed("Instalação e configuração de software", "Software e sistemas", "Instalação e configuração básica. Faixa de referência: R$ 50,00 a R$ 120,00. Licenças pagas à parte.", "85.00", "50.00", "120.00", "por instalação", "1.00"),
                new Seed("Formatação e instalação de sistema operacional", "Assistência técnica / manutenção", "Formatação e instalação do sistema operacional. Faixa de referência: R$ 100,00 a R$ 200,00. Backup, licenças e peças à parte.", "150.00", "100.00", "200.00", "por equipamento", "2.50"),
                new Seed("Manutenção preventiva e limpeza", "Assistência técnica / manutenção", "Manutenção preventiva e limpeza. Faixa de referência: R$ 100,00 a R$ 180,00 por equipamento. Peças e deslocamento à parte.", "140.00", "100.00", "180.00", "por equipamento", "1.50"),
                new Seed("Remoção de vírus e otimização", "Assistência técnica / manutenção", "Análise, remoção de ameaças e otimização. Faixa de referência: R$ 120,00 a R$ 200,00. Casos complexos exigem orçamento.", "160.00", "120.00", "200.00", "por atendimento", "2.00"),
                new Seed("Suporte técnico remoto", "Suporte técnico", "Atendimento remoto por hora. Faixa de referência: R$ 80,00 a R$ 150,00 por hora. A quantidade de horas multiplica a estimativa.", "110.00", "80.00", "150.00", "por hora", "1.00"),
                new Seed("Configuração de roteador e rede simples", "Redes e infraestrutura", "Configuração básica de roteador/rede. Faixa de referência: R$ 150,00 a R$ 300,00. Equipamentos, materiais e deslocamento à parte.", "220.00", "150.00", "300.00", "por atendimento", "2.00"),
                new Seed("Manutenção de computadores para empresas", "Assistência técnica / manutenção", "Sob orçamento. Valor depende da quantidade de equipamentos, contrato, frequência e SLA.", "0.00", "0.00", "0.00", "sob orçamento", "0.00"),
                new Seed("Desenvolvimento ou implantação de software", "Software e sistemas", "Sob orçamento. O valor depende do escopo, integrações, licenças e complexidade.", "0.00", "0.00", "0.00", "sob orçamento", "0.00")
        );
        String now = LocalDateTime.now().format(DATE_FORMAT);
        try (Connection c = connect()) {
            for (Seed seed : seeds) {
                Long id = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM service_catalog WHERE LOWER(name)=LOWER(?) LIMIT 1")) {
                    ps.setString(1, seed.name());
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) id = rs.getLong(1); }
                }
                if (id == null) {
                    String sql = "INSERT INTO service_catalog(name,category,description,default_price,estimated_hours,status,public_min_price,public_max_price,pricing_unit,public_enabled,created_at,updated_at) VALUES(?,?,?,?,?,'Ativo',?,?,?,1,?,?)";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, seed.name()); ps.setString(2, seed.category()); ps.setString(3, seed.description());
                        ps.setBigDecimal(4, new BigDecimal(seed.price())); ps.setBigDecimal(5, new BigDecimal(seed.hours()));
                        ps.setBigDecimal(6, new BigDecimal(seed.min())); ps.setBigDecimal(7, new BigDecimal(seed.max()));
                        ps.setString(8, seed.unit()); ps.setString(9, now); ps.setString(10, now); ps.executeUpdate();
                    }
                } else {
                    String sql = "UPDATE service_catalog SET public_enabled=1, public_min_price=?, public_max_price=?, pricing_unit=?, default_price=CASE WHEN default_price=0 AND ? > 0 THEN ? ELSE default_price END, description=CASE WHEN description='' THEN ? ELSE description END, updated_at=? WHERE id=?";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setBigDecimal(1, new BigDecimal(seed.min())); ps.setBigDecimal(2, new BigDecimal(seed.max())); ps.setString(3, seed.unit());
                        ps.setBigDecimal(4, new BigDecimal(seed.price())); ps.setBigDecimal(5, new BigDecimal(seed.price())); ps.setString(6, seed.description()); ps.setString(7, now); ps.setLong(8, id); ps.executeUpdate();
                    }
                }
            }
        }
    }

    private static void ensureColumn(Connection connection, String column, String definition) throws SQLException {
        ensureTableColumn(connection, "customers", column, definition);
    }

    private static void ensureTableColumn(Connection connection, String table, String column, String definition) throws SQLException {
        if (!Set.of("customers", "service_orders", "service_catalog").contains(table)) throw new IllegalArgumentException("Tabela inválida para migração.");
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT " + column + " FROM " + table + " WHERE 1 = 0").close();
        } catch (SQLException missingColumn) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    /** Cria o primeiro administrador somente quando a tabela de usuários está vazia. */
    private static void ensureInitialAdmin() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM app_users")) {
            if (rs.next() && rs.getLong(1) > 0) return;
        }

        boolean production = "production".equalsIgnoreCase(System.getenv().getOrDefault("APP_ENV", "development"));
        String username = System.getenv().getOrDefault("ADMIN_USERNAME", "admin").trim();
        String password = System.getenv("ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            if (production) {
                throw new IllegalStateException("Configure ADMIN_PASSWORD nas variáveis de ambiente antes de iniciar em produção.");
            }
            password = "Admin@12345!";
            System.out.println("AVISO: usando login local de desenvolvimento: admin / Admin@12345! — troque antes de publicar.");
        }
        if (username.isBlank() || username.length() > 100) {
            throw new IllegalStateException("ADMIN_USERNAME deve ter de 1 a 100 caracteres.");
        }
        if (password.length() < 12) {
            throw new IllegalStateException("ADMIN_PASSWORD deve ter pelo menos 12 caracteres.");
        }

        String sql = "INSERT INTO app_users(username, password_hash, created_at) VALUES(?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, hashPassword(password));
            statement.setString(3, LocalDateTime.now().format(DATE_FORMAT));
            statement.executeUpdate();
        }
        System.out.println("Conta administrativa inicial criada para o usuário: " + username);
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) handleApi(exchange, method, path);
            else serveStatic(exchange, path);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, error(e.getMessage()));
        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500, error("Ocorreu um erro ao acessar o banco de dados."));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, error("Ocorreu um erro inesperado no servidor."));
        } finally {
            exchange.close();
        }
    }

    private static void handleApi(HttpExchange exchange, String method, String path) throws IOException, SQLException {
        if ("/api/health".equals(path) && "GET".equals(method)) {
            try (Connection ignored = connect()) {
                sendJson(exchange, 200, Map.of("status", "ok", "database", DATABASE.postgres ? "PostgreSQL" : "SQLite"));
            }
            return;
        }

        if ("/api/csrf".equals(path) && "GET".equals(method)) {
            Session session = getSession(exchange);
            if (session == null) session = createSession(exchange, null);
            sendJson(exchange, 200, Map.of("csrfToken", session.csrfToken));
            return;
        }

        // Catálogo público: somente serviços ativos explicitamente publicados.
        if ("/api/public-services".equals(path)) {
            if (!"GET".equals(method)) { methodNotAllowed(exchange, "GET"); return; }
            listPublicServices(exchange);
            return;
        }

        // Endpoint público: cria/reutiliza um cadastro e abre uma OS aguardando triagem.
        if ("/api/public-requests".equals(path)) {
            if (!"POST".equals(method)) { methodNotAllowed(exchange, "POST"); return; }
            handlePublicRequest(exchange);
            return;
        }

        if ("/api/login".equals(path)) {
            if (!"POST".equals(method)) { methodNotAllowed(exchange, "POST"); return; }
            Session current = getSession(exchange);
            if (current == null || !validCsrf(exchange, current)) {
                sendJson(exchange, 403, error("Token de segurança inválido. Atualize a página e tente novamente."));
                return;
            }
            JsonObject payload = readJsonObject(exchange);
            String username = readString(payload, "username").trim();
            String password = readString(payload, "password");
            if (username.isBlank() || username.length() > 100 || password.isEmpty() || password.length() > 1024) {
                registerFailedLogin(exchange);
                sendJson(exchange, 401, error("Usuário ou senha inválidos."));
                return;
            }
            if (isLoginBlocked(exchange)) {
                sendJson(exchange, 429, error("Muitas tentativas de login. Aguarde 15 minutos antes de tentar novamente."));
                return;
            }
            String storedHash = findPasswordHash(username);
            boolean passwordMatches = verifyPassword(password, storedHash == null ? DUMMY_PASSWORD_HASH : storedHash);
            if (storedHash == null || !passwordMatches) {
                registerFailedLogin(exchange);
                sendJson(exchange, 401, error("Usuário ou senha inválidos."));
                return;
            }
            LOGIN_ATTEMPTS.remove(clientKey(exchange));
            SESSIONS.remove(current.id); // Troca o identificador após login para reduzir risco de fixation.
            Session authenticated = createSession(exchange, username);
            sendJson(exchange, 200, Map.of("authenticated", true, "username", username, "csrfToken", authenticated.csrfToken));
            return;
        }

        if ("/api/logout".equals(path)) {
            if (!"POST".equals(method)) { methodNotAllowed(exchange, "POST"); return; }
            Session current = getSession(exchange);
            if (current == null || !validCsrf(exchange, current)) {
                sendJson(exchange, 403, error("Token de segurança inválido. Atualize a página e tente novamente."));
                return;
            }
            SESSIONS.remove(current.id);
            clearSessionCookie(exchange);
            sendJson(exchange, 200, Map.of("ok", true));
            return;
        }

        if ("/api/me".equals(path) && "GET".equals(method)) {
            Session session = authenticatedSession(exchange);
            if (session == null) {
                sendJson(exchange, 401, error("Entre na sua conta para continuar."));
                return;
            }
            sendJson(exchange, 200, Map.of("authenticated", true, "username", session.username));
            return;
        }

        boolean customerPath = path.equals("/api/customers") || path.startsWith("/api/customers/");
        boolean businessPath = path.equals("/api/companies") || path.startsWith("/api/companies/")
                || path.equals("/api/services") || path.startsWith("/api/services/")
                || path.equals("/api/work-orders") || path.startsWith("/api/work-orders/");
        boolean operationsPath = operationsModuleFromPath(path) != null;
        boolean paymentPath = Set.of("/api/payment-settings", "/api/pix/generate", "/api/pix/confirm").contains(path);
        if (!customerPath && !businessPath && !operationsPath && !paymentPath) {
            sendJson(exchange, 404, error("Endpoint não encontrado."));
            return;
        }
        Session session = authenticatedSession(exchange);
        if (session == null) {
            sendJson(exchange, 401, error("Sua sessão expirou. Entre novamente."));
            return;
        }
        if (!isSafeMethod(method) && !validCsrf(exchange, session)) {
            sendJson(exchange, 403, error("Token de segurança inválido. Atualize a página e tente novamente."));
            return;
        }
        if (paymentPath) handlePaymentApi(exchange, method, path);
        else if (customerPath) handleCustomerApi(exchange, method, path);
        else if (businessPath) handleBusinessApi(exchange, method, path);
        else handleOperationsApi(exchange, method, path);
    }

    private static void listPublicServices(HttpExchange exchange) throws SQLException, IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT id,name,category,description,default_price,public_min_price,public_max_price,pricing_unit FROM service_catalog WHERE status='Ativo' AND public_enabled=1 ORDER BY category,name";
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id")); row.put("name", rs.getString("name")); row.put("category", safePublicCategory(rs.getString("category")));
                row.put("description", rs.getString("description")); row.put("defaultPrice", rs.getBigDecimal("default_price"));
                row.put("minPrice", rs.getBigDecimal("public_min_price")); row.put("maxPrice", rs.getBigDecimal("public_max_price"));
                row.put("pricingUnit", rs.getString("pricing_unit"));
                rows.add(row);
            }
        }
        sendJson(exchange, 200, rows);
    }

    private static String safePublicCategory(String category) {
        Set<String> allowed = Set.of("Assistência técnica / manutenção", "Software e sistemas", "Redes e infraestrutura", "Suporte técnico", "Consultoria", "Outro");
        return allowed.contains(category) ? category : "Outro";
    }

    private static void handlePublicRequest(HttpExchange exchange) throws IOException, SQLException {
        Session session = getSession(exchange);
        if (session == null || !validCsrf(exchange, session)) {
            sendJson(exchange, 403, error("Atualize a página e tente enviar a solicitação novamente."));
            return;
        }
        if (publicRequestBlocked(exchange)) {
            sendJson(exchange, 429, error("Muitas solicitações foram enviadas deste acesso. Aguarde alguns minutos e tente novamente."));
            return;
        }

        JsonObject payload = readJsonObject(exchange);
        // Campo honeypot escondido: respostas plausíveis para bots sem criar registros.
        if (!readString(payload, "website").isBlank()) {
            sendJson(exchange, 201, Map.of("received", true, "requestNumber", "TD-" + randomToken(5).toUpperCase()));
            return;
        }

        String personType = readString(payload, "personType").trim();
        String name = readString(payload, "name").trim();
        String tradeName = readString(payload, "tradeName").trim();
        String contactName = readString(payload, "contactName").trim();
        String email = readString(payload, "email").trim().toLowerCase();
        String phone = readString(payload, "phone").trim();
        String serviceType = readString(payload, "serviceType").trim();
        long selectedServiceId;
        try { selectedServiceId = Long.parseLong(readString(payload, "serviceId").trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Selecione um serviço do catálogo para calcular a estimativa."); }
        int quantity = 1;
        String quantityText = readString(payload, "quantity").trim();
        if (!quantityText.isBlank()) {
            try { quantity = Integer.parseInt(quantityText); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Informe uma quantidade válida."); }
        }
        String title = readString(payload, "title").trim();
        String description = readString(payload, "description").trim();
        String serviceMode = readString(payload, "serviceMode").trim();
        String contactPreference = readString(payload, "contactPreference").trim();
        String address = readString(payload, "address").trim();
        String city = readString(payload, "city").trim();
        String state = readString(payload, "state").trim().toUpperCase();
        String zipCode = readString(payload, "zipCode").trim();
        boolean consent = payload.has("consent") && payload.get("consent").isJsonPrimitive()
                && payload.get("consent").getAsBoolean();

        if (!Set.of("PF", "PJ").contains(personType)) throw new IllegalArgumentException("Selecione pessoa física ou pessoa jurídica.");
        if (name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Informe seu nome ou a razão social da empresa (até 120 caracteres).");
        if (email.isBlank() && phone.isBlank()) throw new IllegalArgumentException("Informe ao menos um telefone ou e-mail para contato.");
        if (!email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Informe um e-mail válido.");
        if (phone.isBlank() || phone.replaceAll("\\D", "").length() < 8) throw new IllegalArgumentException("Informe um telefone ou WhatsApp válido para contato.");
        if (!Set.of("Assistência técnica / manutenção", "Software e sistemas", "Redes e infraestrutura", "Suporte técnico", "Consultoria", "Outro").contains(serviceType)) throw new IllegalArgumentException("Selecione uma área de atendimento válida.");
        if (title.length() < 4 || title.length() > 160) throw new IllegalArgumentException("Descreva o assunto do chamado em 4 a 160 caracteres.");
        if (description.length() < 15 || description.length() > 3000) throw new IllegalArgumentException("Descreva sua necessidade em pelo menos 15 caracteres (máximo de 3000).");
        if (!Set.of("Presencial", "Remoto", "A combinar").contains(serviceMode)) throw new IllegalArgumentException("Selecione a modalidade de atendimento.");
        if (!Set.of("WhatsApp", "Telefone", "E-mail", "Sem preferência").contains(contactPreference)) throw new IllegalArgumentException("Selecione uma forma de contato válida.");
        if ("E-mail".equals(contactPreference) && email.isBlank()) throw new IllegalArgumentException("Informe seu e-mail para solicitar contato por e-mail.");
        if (!consent) throw new IllegalArgumentException("Autorize o contato necessário para responder à solicitação.");
        if (tradeName.length() > 160 || contactName.length() > 120 || email.length() > 160 || phone.length() > 40
                || address.length() > 180 || city.length() > 100 || state.length() > 2 || zipCode.length() > 12) {
            throw new IllegalArgumentException("Um dos campos ultrapassou o limite permitido.");
        }
        if ("PF".equals(personType)) { tradeName = ""; contactName = ""; }
        if ("PJ".equals(personType) && tradeName.isBlank()) tradeName = name;

        PublicQuote quote = findPublicQuote(selectedServiceId, quantity);
        serviceType = quote.category();
        String selectedServiceName = quote.name();
        String now = LocalDateTime.now().format(DATE_FORMAT);
        Long customerId = null;
        long orderId;
        String recordType = "PJ".equals(personType) ? "Empresa contratante" : "Cliente avulso";
        String customerCompany = "PJ".equals(personType) ? tradeName : "";
        String customerNotes = "Cadastro criado pelo portal público. Aguardar triagem da solicitação.";
        String local = String.join(" ", java.util.stream.Stream.of(address, city, state, zipCode).filter(v -> v != null && !v.isBlank()).toList()).trim();
        String quoteNote = quote.quoteOnly() ? "Estimativa: serviço sob orçamento; aguarda análise manual." :
                "Estimativa automática inicial: R$ " + quote.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + (quote.minPrice().signum() > 0 || quote.maxPrice().signum() > 0 ? " (faixa indicativa R$ " + quote.minPrice().multiply(BigDecimal.valueOf(quote.multiplier())).setScale(2, RoundingMode.HALF_UP).toPlainString() + " a R$ " + quote.maxPrice().multiply(BigDecimal.valueOf(quote.multiplier())).setScale(2, RoundingMode.HALF_UP).toPlainString() + ")" : "")
                + ". Valor sujeito à revisão/aprovação do administrador; não cobrar antes da aprovação.";
        String orderNotes = "Origem: portal público. Serviço solicitado: " + selectedServiceName + ". Unidade: " + quote.unit() + ". Quantidade calculada: " + quote.multiplier() + ". " + quoteNote
                + " Contato preferencial: " + contactPreference + ". Modalidade: " + serviceMode + "."
                + (local.isBlank() ? "" : " Local informado: " + local + ".")
                + " Consentimento para contato registrado no envio.";

        try (Connection connection = connect()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                customerId = findExistingPublicCustomer(connection, personType, email, phone, name);
                if (customerId == null) {
                    String customerSql = "INSERT INTO customers(name,email,phone,cpf,birth_date,company,status,address,neighborhood,city,state,zip_code,notes,person_type,record_type,trade_name,contact_name,service_type,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                    try (PreparedStatement ps = connection.prepareStatement(DATABASE.postgres ? customerSql + " RETURNING id" : customerSql)) {
                        ps.setString(1, name); ps.setString(2, email); ps.setString(3, phone); ps.setString(4, ""); ps.setString(5, "");
                        ps.setString(6, customerCompany); ps.setString(7, "Pendente"); ps.setString(8, address); ps.setString(9, "");
                        ps.setString(10, city); ps.setString(11, state); ps.setString(12, zipCode); ps.setString(13, customerNotes);
                        ps.setString(14, personType); ps.setString(15, recordType); ps.setString(16, tradeName); ps.setString(17, contactName);
                        ps.setString(18, serviceType); ps.setString(19, now); ps.setString(20, now);
                        if (DATABASE.postgres) {
                            try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Não foi possível criar o cadastro da solicitação."); customerId = rs.getLong(1); }
                        } else {
                            ps.executeUpdate();
                            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                                if (!rs.next()) throw new SQLException("Não foi possível recuperar o cadastro da solicitação."); customerId = rs.getLong(1);
                            }
                        }
                    }
                }

                String finalTitle = title;
                String finalServiceType = serviceType;
                String finalDescription = description;
                String finalOrderNotes = orderNotes;
                String orderSql = "INSERT INTO service_orders(company_id,customer_id,service_id,title,service_type,description,status,priority,technician,scheduled_date,completed_date,price,notes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                try (PreparedStatement ps = connection.prepareStatement(DATABASE.postgres ? orderSql + " RETURNING id" : orderSql)) {
                    ps.setNull(1, java.sql.Types.BIGINT); ps.setLong(2, customerId); ps.setLong(3, selectedServiceId);
                    ps.setString(4, finalTitle); ps.setString(5, finalServiceType); ps.setString(6, finalDescription);
                    ps.setString(7, "Solicitação recebida"); ps.setString(8, "Normal"); ps.setString(9, ""); ps.setString(10, ""); ps.setString(11, "");
                    ps.setBigDecimal(12, quote.amount()); ps.setString(13, finalOrderNotes); ps.setString(14, now); ps.setString(15, now);
                    if (DATABASE.postgres) {
                        try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Não foi possível criar a ordem de serviço."); orderId = rs.getLong(1); }
                    } else {
                        ps.executeUpdate();
                        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                            if (!rs.next()) throw new SQLException("Não foi possível recuperar a ordem de serviço."); orderId = rs.getLong(1);
                        }
                    }
                }
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                if (ex instanceof IllegalArgumentException iae) throw iae;
                if (ex instanceof SQLException sqle) throw sqle;
                throw new SQLException("Não foi possível registrar a solicitação pública.", ex);
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("received", true); response.put("requestNumber", "OS-" + String.format("%05d", orderId));
        response.put("message", "Sua solicitação foi registrada com sucesso.");
        response.put("serviceName", quote.name()); response.put("quoteOnly", quote.quoteOnly());
        response.put("estimatedPrice", quote.amount()); response.put("minPrice", quote.minPrice().multiply(BigDecimal.valueOf(quote.multiplier())));
        response.put("maxPrice", quote.maxPrice().multiply(BigDecimal.valueOf(quote.multiplier()))); response.put("pricingUnit", quote.unit());
        sendJson(exchange, 201, response);
    }

    private static Long findExistingPublicCustomer(Connection connection, String personType, String email, String phone, String name) throws SQLException {
        String normalizedName = normalizeLookupName(name);
        if (email != null && !email.isBlank()) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT id, name FROM customers WHERE LOWER(email)=LOWER(?) AND person_type=? ORDER BY id DESC")) {
                ps.setString(1, email); ps.setString(2, personType);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (normalizedName.equals(normalizeLookupName(rs.getString("name")))) return rs.getLong("id");
                    }
                }
            }
        }
        String submittedDigits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (submittedDigits.length() >= 8) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT id, phone, name FROM customers WHERE person_type=? AND phone<>'' ORDER BY id DESC")) {
                ps.setString(1, personType);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String existingDigits = rs.getString("phone").replaceAll("\\D", "");
                        if (submittedDigits.equals(existingDigits) && normalizedName.equals(normalizeLookupName(rs.getString("name")))) return rs.getLong("id");
                    }
                }
            }
        }
        return null;
    }

    private static String normalizeLookupName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean publicRequestBlocked(HttpExchange exchange) {
        String key = clientKey(exchange);
        long now = System.currentTimeMillis();
        PublicRequestAttempt attempt = PUBLIC_REQUEST_ATTEMPTS.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous.windowStarted >= PUBLIC_REQUEST_WINDOW_MS) return new PublicRequestAttempt(1, now);
            synchronized (previous) { previous.requests++; return previous; }
        });
        if (PUBLIC_REQUEST_ATTEMPTS.size() > 5000) PUBLIC_REQUEST_ATTEMPTS.entrySet().removeIf(entry -> now - entry.getValue().windowStarted >= PUBLIC_REQUEST_WINDOW_MS);
        synchronized (attempt) { return attempt.requests > MAX_PUBLIC_REQUESTS_PER_WINDOW; }
    }

    private static void handleCustomerApi(HttpExchange exchange, String method, String path) throws IOException, SQLException {
        if ("/api/customers".equals(path)) {
            switch (method) {
                case "GET" -> listCustomers(exchange);
                case "POST" -> createCustomer(exchange);
                default -> methodNotAllowed(exchange, "GET, POST");
            }
            return;
        }

        String idPart = path.substring("/api/customers/".length());
        if (idPart.isBlank() || idPart.contains("/")) {
            sendJson(exchange, 404, error("Cliente não encontrado."));
            return;
        }
        long id;
        try { id = Long.parseLong(idPart); }
        catch (NumberFormatException e) {
            sendJson(exchange, 400, error("O identificador do cliente é inválido."));
            return;
        }
        switch (method) {
            case "GET" -> getCustomer(exchange, id);
            case "PUT" -> updateCustomer(exchange, id);
            case "DELETE" -> deleteCustomer(exchange, id);
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private static void listCustomers(HttpExchange exchange) throws SQLException, IOException {
        String query = getQueryParameter(exchange.getRequestURI().getRawQuery(), "search");
        String sql = "SELECT id, name, email, phone, cpf, birth_date, company, status, address, neighborhood, city, state, zip_code, notes, person_type, record_type, trade_name, contact_name, service_type, legacy_company_id, created_at, updated_at FROM customers "
                + "WHERE (? = '' OR name LIKE ? OR email LIKE ? OR phone LIKE ? OR cpf LIKE ? OR company LIKE ? OR city LIKE ? OR state LIKE ? OR trade_name LIKE ? OR service_type LIKE ? OR record_type LIKE ?) ORDER BY id DESC";
        List<Map<String, Object>> customers = new ArrayList<>();
        String pattern = "%" + query + "%";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, query);
            for (int i = 2; i <= 11; i++) statement.setString(i, pattern);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) customers.add(customerFrom(rs));
            }
        }
        sendJson(exchange, 200, customers);
    }

    private static void getCustomer(HttpExchange exchange, long id) throws SQLException, IOException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, email, phone, cpf, birth_date, company, status, address, neighborhood, city, state, zip_code, notes, person_type, record_type, trade_name, contact_name, service_type, legacy_company_id, created_at, updated_at FROM customers WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) sendJson(exchange, 200, customerFrom(rs));
                else sendJson(exchange, 404, error("Cliente não encontrado."));
            }
        }
    }

    private static void createCustomer(HttpExchange exchange) throws IOException, SQLException {
        CustomerInput input = readCustomerInput(exchange);
        String now = LocalDateTime.now().format(DATE_FORMAT);
        String baseSql = "INSERT INTO customers(name, email, phone, cpf, birth_date, company, status, address, neighborhood, city, state, zip_code, notes, person_type, record_type, trade_name, contact_name, service_type, created_at, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        long id;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                DATABASE.postgres ? baseSql + " RETURNING id" : baseSql)) {
            bindInput(statement, input);
            bindExtendedCustomerInput(statement, input);
            statement.setString(19, now);
            statement.setString(20, now);
            if (DATABASE.postgres) {
                try (ResultSet rs = statement.executeQuery()) { id = rs.next() ? rs.getLong(1) : -1; }
            } else {
                statement.executeUpdate();
                try (Statement idStatement = connection.createStatement(); ResultSet rs = idStatement.executeQuery("SELECT last_insert_rowid()")) {
                    id = rs.next() ? rs.getLong(1) : -1;
                }
            }
            sendJson(exchange, 201, getCustomerMap(connection, id));
        }
    }

    private static void updateCustomer(HttpExchange exchange, long id) throws IOException, SQLException {
        CustomerInput input = readCustomerInput(exchange);
        String sql = "UPDATE customers SET name = ?, email = ?, phone = ?, cpf = ?, birth_date = ?, company = ?, status = ?, address = ?, neighborhood = ?, city = ?, state = ?, zip_code = ?, notes = ?, person_type = ?, record_type = ?, trade_name = ?, contact_name = ?, service_type = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindInput(statement, input);
            bindExtendedCustomerInput(statement, input);
            statement.setString(19, LocalDateTime.now().format(DATE_FORMAT));
            statement.setLong(20, id);
            int changed = statement.executeUpdate();
            if (changed == 0) sendJson(exchange, 404, error("Cliente não encontrado."));
            else sendJson(exchange, 200, getCustomerMap(connection, id));
        }
    }

    private static void deleteCustomer(HttpExchange exchange, long id) throws SQLException, IOException {
        try (Connection connection = connect()) {
            try (PreparedStatement countOrders = connection.prepareStatement("SELECT COUNT(*) FROM service_orders WHERE customer_id = ?")) {
                countOrders.setLong(1, id);
                try (ResultSet rs = countOrders.executeQuery()) {
                    if (rs.next() && rs.getLong(1) > 0) {
                        sendJson(exchange, 409, error("Este cadastro possui ordens de serviço vinculadas. Altere o status para Inativo para preservar o histórico dos atendimentos."));
                        return;
                    }
                }
            }
            Long legacyCompanyId = null;
            try (PreparedStatement lookup = connection.prepareStatement("SELECT legacy_company_id FROM customers WHERE id = ?")) {
                lookup.setLong(1, id);
                try (ResultSet rs = lookup.executeQuery()) {
                    if (!rs.next()) { sendJson(exchange, 404, error("Cadastro não encontrado.")); return; }
                    long value = rs.getLong("legacy_company_id");
                    if (!rs.wasNull()) legacyCompanyId = value;
                }
            }
            if (legacyCompanyId != null) {
                try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM service_orders WHERE company_id = ?")) {
                    count.setLong(1, legacyCompanyId);
                    try (ResultSet rs = count.executeQuery()) {
                        if (rs.next() && rs.getLong(1) > 0) {
                            sendJson(exchange, 409, error("Este contratante possui ordens de serviço históricas. Para preservar o histórico, altere o status para Inativo em vez de excluir."));
                            return;
                        }
                    }
                }
                try (PreparedStatement removeLegacy = connection.prepareStatement("DELETE FROM companies WHERE id = ?")) {
                    removeLegacy.setLong(1, legacyCompanyId);
                    removeLegacy.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM customers WHERE id = ?")) {
                statement.setLong(1, id);
                if (statement.executeUpdate() == 0) sendJson(exchange, 404, error("Cadastro não encontrado."));
                else sendJson(exchange, 200, Map.of("message", "Cadastro excluído com sucesso."));
            }
        }
    }

    private static void handleBusinessApi(HttpExchange exchange, String method, String path) throws IOException, SQLException {
        String resource;
        String prefix;
        if (path.equals("/api/companies") || path.startsWith("/api/companies/")) {
            resource = "companies"; prefix = "/api/companies";
        } else if (path.equals("/api/services") || path.startsWith("/api/services/")) {
            resource = "services"; prefix = "/api/services";
        } else {
            resource = "work-orders"; prefix = "/api/work-orders";
        }
        if (path.equals(prefix)) {
            switch (method) {
                case "GET" -> {
                    if ("companies".equals(resource)) listCompanies(exchange);
                    else if ("services".equals(resource)) listServices(exchange);
                    else listWorkOrders(exchange);
                }
                case "POST" -> {
                    if ("companies".equals(resource)) saveCompany(exchange, null);
                    else if ("services".equals(resource)) saveService(exchange, null);
                    else saveWorkOrder(exchange, null);
                }
                default -> methodNotAllowed(exchange, "GET, POST");
            }
            return;
        }
        String idPart = path.substring(prefix.length() + 1);
        if (idPart.isBlank() || idPart.contains("/")) { sendJson(exchange, 404, error("Registro não encontrado.")); return; }
        long id;
        try { id = Long.parseLong(idPart); }
        catch (NumberFormatException e) { sendJson(exchange, 400, error("O identificador informado é inválido.")); return; }
        switch (method) {
            case "GET" -> getBusinessRecord(exchange, resource, id);
            case "PUT" -> {
                if ("companies".equals(resource)) saveCompany(exchange, id);
                else if ("services".equals(resource)) saveService(exchange, id);
                else saveWorkOrder(exchange, id);
            }
            case "DELETE" -> deleteBusinessRecord(exchange, resource, id);
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private static void listCompanies(HttpExchange exchange) throws SQLException, IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM companies ORDER BY id DESC")) {
            while (rs.next()) rows.add(companyFrom(rs));
        }
        sendJson(exchange, 200, rows);
    }

    private static void listServices(HttpExchange exchange) throws SQLException, IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM service_catalog ORDER BY id DESC")) {
            while (rs.next()) rows.add(serviceFrom(rs));
        }
        sendJson(exchange, 200, rows);
    }

    private static void listWorkOrders(HttpExchange exchange) throws SQLException, IOException {
        String sql = "SELECT o.*, co.name AS company_name, co.trade_name AS company_trade_name, "
                + "cu.name AS customer_name, sv.name AS service_name FROM service_orders o "
                + "LEFT JOIN companies co ON co.id = o.company_id LEFT JOIN customers cu ON cu.id = o.customer_id "
                + "LEFT JOIN service_catalog sv ON sv.id = o.service_id ORDER BY o.id DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) rows.add(workOrderFrom(rs));
        }
        sendJson(exchange, 200, rows);
    }

    private static void getBusinessRecord(HttpExchange exchange, String resource, long id) throws SQLException, IOException {
        String sql;
        if ("companies".equals(resource)) sql = "SELECT * FROM companies WHERE id = ?";
        else if ("services".equals(resource)) sql = "SELECT * FROM service_catalog WHERE id = ?";
        else sql = "SELECT o.*, co.name AS company_name, co.trade_name AS company_trade_name, cu.name AS customer_name, sv.name AS service_name "
                + "FROM service_orders o LEFT JOIN companies co ON co.id=o.company_id LEFT JOIN customers cu ON cu.id=o.customer_id "
                + "LEFT JOIN service_catalog sv ON sv.id=o.service_id WHERE o.id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { sendJson(exchange, 404, error("Registro não encontrado.")); return; }
                sendJson(exchange, 200, "companies".equals(resource) ? companyFrom(rs) : "services".equals(resource) ? serviceFrom(rs) : workOrderFrom(rs));
            }
        }
    }

    private static void saveCompany(HttpExchange exchange, Long id) throws IOException, SQLException {
        JsonObject o = readJsonObject(exchange);
        String name = readString(o, "name").trim();
        String trade = readString(o, "tradeName").trim();
        String cnpj = readString(o, "cnpj").trim();
        String email = readString(o, "email").trim();
        String phone = readString(o, "phone").trim();
        String contact = readString(o, "contactName").trim();
        String address = readString(o, "address").trim();
        String city = readString(o, "city").trim();
        String state = readString(o, "state").trim().toUpperCase();
        String status = readString(o, "status").trim();
        String notes = readString(o, "notes").trim();
        if (name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Informe o nome da empresa (até 160 caracteres).");
        if (!email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Informe um e-mail válido para a empresa.");
        if (status.isBlank()) status = "Ativa";
        if (!Set.of("Ativa", "Inativa", "Pendente").contains(status)) throw new IllegalArgumentException("Selecione um status válido para a empresa.");
        if (trade.length()>160 || cnpj.length()>24 || email.length()>160 || phone.length()>40 || contact.length()>120 || address.length()>180 || city.length()>100 || state.length()>2 || notes.length()>2000) throw new IllegalArgumentException("Um dos campos da empresa ultrapassou o limite permitido.");
        final String companyStatus = status;
        String now = LocalDateTime.now().format(DATE_FORMAT);
        String sql;
        if (id == null) {
            sql = "INSERT INTO companies(name,trade_name,cnpj,email,phone,contact_name,address,city,state,status,notes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";
            long newId = insertWithId(sql, ps -> { int i=1; ps.setString(i++,name); ps.setString(i++,trade); ps.setString(i++,cnpj); ps.setString(i++,email); ps.setString(i++,phone); ps.setString(i++,contact); ps.setString(i++,address); ps.setString(i++,city); ps.setString(i++,state); ps.setString(i++,companyStatus); ps.setString(i++,notes); ps.setString(i++,now); ps.setString(i,now); });
            sendJson(exchange, 201, getCompanyMap(newId));
        } else {
            sql = "UPDATE companies SET name=?,trade_name=?,cnpj=?,email=?,phone=?,contact_name=?,address=?,city=?,state=?,status=?,notes=?,updated_at=? WHERE id=?";
            try (Connection c=connect(); PreparedStatement ps=c.prepareStatement(sql)) {
                int i=1; ps.setString(i++,name); ps.setString(i++,trade); ps.setString(i++,cnpj); ps.setString(i++,email); ps.setString(i++,phone); ps.setString(i++,contact); ps.setString(i++,address); ps.setString(i++,city); ps.setString(i++,state); ps.setString(i++,status); ps.setString(i++,notes); ps.setString(i++,now); ps.setLong(i,id);
                if (ps.executeUpdate()==0) { sendJson(exchange,404,error("Empresa não encontrada.")); return; }
            }
            sendJson(exchange, 200, getCompanyMap(id));
        }
    }

    private static void saveService(HttpExchange exchange, Long id) throws IOException, SQLException {
        JsonObject o = readJsonObject(exchange);
        String name=readString(o,"name").trim(), category=readString(o,"category").trim(), description=readString(o,"description").trim(), status=readString(o,"status").trim();
        BigDecimal price=parseMoney(readString(o,"defaultPrice"),"Preço padrão");
        BigDecimal minPrice=parseMoney(readString(o,"minPrice"),"Preço mínimo");
        BigDecimal maxPrice=parseMoney(readString(o,"maxPrice"),"Preço máximo");
        BigDecimal hours=parseMoney(readString(o,"estimatedHours"),"Tempo estimado");
        String pricingUnit=readString(o,"pricingUnit").trim();
        boolean publicEnabled=o.has("publicEnabled") && o.get("publicEnabled").isJsonPrimitive() && o.get("publicEnabled").getAsBoolean();
        if (name.isBlank() || name.length()>140) throw new IllegalArgumentException("Informe o nome do serviço (até 140 caracteres).");
        if (category.length()>100 || description.length()>2000) throw new IllegalArgumentException("A categoria ou descrição do serviço é muito longa.");
        if (price.signum()<0 || minPrice.signum()<0 || maxPrice.signum()<0 || hours.signum()<0 || hours.compareTo(new BigDecimal("10000"))>0) throw new IllegalArgumentException("Preço e tempo estimado não podem ser negativos ou excessivos.");
        if (minPrice.compareTo(maxPrice)>0) throw new IllegalArgumentException("O preço mínimo não pode ser maior que o máximo.");
        if (pricingUnit.isBlank()) pricingUnit="por atendimento";
        if (!Set.of("por atendimento","por instalação","por equipamento","por hora","sob orçamento").contains(pricingUnit)) throw new IllegalArgumentException("Selecione uma unidade de cobrança válida.");
        if (status.isBlank()) status="Ativo";
        if (!Set.of("Ativo","Inativo").contains(status)) throw new IllegalArgumentException("Selecione um status válido para o serviço.");
        final String serviceStatus=status;
        final String servicePricingUnit=pricingUnit;
        String now=LocalDateTime.now().format(DATE_FORMAT);
        if (id==null) {
            String sql="INSERT INTO service_catalog(name,category,description,default_price,estimated_hours,status,public_min_price,public_max_price,pricing_unit,public_enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
            long newId=insertWithId(sql,ps->{ps.setString(1,name);ps.setString(2,category);ps.setString(3,description);ps.setBigDecimal(4,price);ps.setBigDecimal(5,hours);ps.setString(6,serviceStatus);ps.setBigDecimal(7,minPrice);ps.setBigDecimal(8,maxPrice);ps.setString(9,servicePricingUnit);ps.setInt(10,publicEnabled?1:0);ps.setString(11,now);ps.setString(12,now);});
            sendJson(exchange,201,getServiceMap(newId));
        } else {
            String sql="UPDATE service_catalog SET name=?,category=?,description=?,default_price=?,estimated_hours=?,status=?,public_min_price=?,public_max_price=?,pricing_unit=?,public_enabled=?,updated_at=? WHERE id=?";
            try(Connection c=connect();PreparedStatement ps=c.prepareStatement(sql)){ps.setString(1,name);ps.setString(2,category);ps.setString(3,description);ps.setBigDecimal(4,price);ps.setBigDecimal(5,hours);ps.setString(6,status);ps.setBigDecimal(7,minPrice);ps.setBigDecimal(8,maxPrice);ps.setString(9,pricingUnit);ps.setInt(10,publicEnabled?1:0);ps.setString(11,now);ps.setLong(12,id);if(ps.executeUpdate()==0){sendJson(exchange,404,error("Serviço não encontrado."));return;}}
            sendJson(exchange,200,getServiceMap(id));
        }
    }

    private record PublicQuote(long serviceId, String name, String category, String unit, BigDecimal basePrice, BigDecimal minPrice, BigDecimal maxPrice, int multiplier, BigDecimal amount, boolean quoteOnly) { }

    private static PublicQuote findPublicQuote(long serviceId, int quantity) throws SQLException {
        if (serviceId <= 0) throw new IllegalArgumentException("Selecione um serviço do catálogo.");
        if (quantity < 1 || quantity > 20) throw new IllegalArgumentException("A quantidade deve ficar entre 1 e 20.");
        String sql="SELECT id,name,category,default_price,public_min_price,public_max_price,pricing_unit FROM service_catalog WHERE id=? AND status='Ativo' AND public_enabled=1";
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement(sql)){
            ps.setLong(1,serviceId);
            try(ResultSet rs=ps.executeQuery()){
                if(!rs.next()) throw new IllegalArgumentException("O serviço escolhido não está disponível no portal. Atualize a página e selecione outro.");
                String unit=rs.getString("pricing_unit"); BigDecimal base=rs.getBigDecimal("default_price");
                BigDecimal min=rs.getBigDecimal("public_min_price"); BigDecimal max=rs.getBigDecimal("public_max_price");
                boolean perUnit=Set.of("por hora","por equipamento","por instalação").contains(unit);
                int multiplier=perUnit?quantity:1;
                boolean custom="sob orçamento".equalsIgnoreCase(unit) || base.signum()==0;
                BigDecimal amount=custom?BigDecimal.ZERO:base.multiply(BigDecimal.valueOf(multiplier)).setScale(2,RoundingMode.HALF_UP);
                return new PublicQuote(rs.getLong("id"),rs.getString("name"),safePublicCategory(rs.getString("category")),unit,base,min,max,multiplier,amount,custom);
            }
        }
    }

    private static void saveWorkOrder(HttpExchange exchange, Long id) throws IOException, SQLException {
        JsonObject o=readJsonObject(exchange);
        Long companyId=parseOptionalLong(readString(o,"companyId"),"Empresa"), customerId=parseOptionalLong(readString(o,"customerId"),"Cliente"), serviceId=parseOptionalLong(readString(o,"serviceId"),"Serviço");
        String title=readString(o,"title").trim(), serviceType=readString(o,"serviceType").trim(), description=readString(o,"description").trim(), status=readString(o,"status").trim(), priority=readString(o,"priority").trim(), technician=readString(o,"technician").trim(), scheduled=readString(o,"scheduledDate").trim(), completed=readString(o,"completedDate").trim(), notes=readString(o,"notes").trim();
        BigDecimal price=parseMoney(readString(o,"price"),"Valor do serviço");
        if (companyId==null && customerId==null) throw new IllegalArgumentException("Selecione a pessoa ou empresa contratante.");
        if (serviceType.isBlank()) serviceType="Assistência técnica / manutenção";
        if (!Set.of("Assistência técnica / manutenção", "Software e sistemas", "Redes e infraestrutura", "Suporte técnico", "Consultoria", "Outro").contains(serviceType)) throw new IllegalArgumentException("Selecione uma área de chamado válida.");
        if (title.isBlank() || title.length()>160) throw new IllegalArgumentException("Informe o título ou serviço executado (até 160 caracteres).");
        if (serviceType.length()>80 || description.length()>3000 || technician.length()>120 || notes.length()>2000) throw new IllegalArgumentException("Um dos campos da ordem de serviço ultrapassou o limite permitido.");
        if (price.signum()<0) throw new IllegalArgumentException("O valor do serviço não pode ser negativo.");
        if (status.isBlank()) status="Aberta";
        if (!Set.of("Solicitação recebida","Orçamento em análise","Orçamento enviado","Aguardando pagamento","Aberta","Em andamento","Aguardando peça","Concluída","Cancelada").contains(status)) throw new IllegalArgumentException("Selecione um status válido para a ordem de serviço.");
        if (priority.isBlank()) priority="Normal";
        if (!Set.of("Baixa","Normal","Alta","Urgente").contains(priority)) throw new IllegalArgumentException("Selecione uma prioridade válida.");
        if (companyId!=null && !recordExists("companies",companyId)) throw new IllegalArgumentException("A empresa selecionada não existe.");
        if (customerId!=null && !recordExists("customers",customerId)) throw new IllegalArgumentException("O cliente selecionado não existe.");
        if (serviceId!=null && !recordExists("service_catalog",serviceId)) throw new IllegalArgumentException("O serviço selecionado não existe.");
        final String orderStatus=status; final String orderPriority=priority;
        String now=LocalDateTime.now().format(DATE_FORMAT);
        if (id==null) {
            String sql="INSERT INTO service_orders(company_id,customer_id,service_id,title,service_type,description,status,priority,technician,scheduled_date,completed_date,price,notes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            final String orderServiceType=serviceType;
            long newId=insertWithId(sql,ps->{setNullableLong(ps,1,companyId);setNullableLong(ps,2,customerId);setNullableLong(ps,3,serviceId);ps.setString(4,title);ps.setString(5,orderServiceType);ps.setString(6,description);ps.setString(7,orderStatus);ps.setString(8,orderPriority);ps.setString(9,technician);ps.setString(10,scheduled);ps.setString(11,completed);ps.setBigDecimal(12,price);ps.setString(13,notes);ps.setString(14,now);ps.setString(15,now);});
            sendJson(exchange,201,getWorkOrderMap(newId));
        } else {
            String sql="UPDATE service_orders SET company_id=?,customer_id=?,service_id=?,title=?,service_type=?,description=?,status=?,priority=?,technician=?,scheduled_date=?,completed_date=?,price=?,notes=?,updated_at=? WHERE id=?";
            try(Connection c=connect();PreparedStatement ps=c.prepareStatement(sql)){setNullableLong(ps,1,companyId);setNullableLong(ps,2,customerId);setNullableLong(ps,3,serviceId);ps.setString(4,title);ps.setString(5,serviceType);ps.setString(6,description);ps.setString(7,orderStatus);ps.setString(8,orderPriority);ps.setString(9,technician);ps.setString(10,scheduled);ps.setString(11,completed);ps.setBigDecimal(12,price);ps.setString(13,notes);ps.setString(14,now);ps.setLong(15,id);if(ps.executeUpdate()==0){sendJson(exchange,404,error("Ordem de serviço não encontrada."));return;}}
            sendJson(exchange,200,getWorkOrderMap(id));
        }
    }

    private static void deleteBusinessRecord(HttpExchange exchange, String resource, long id) throws SQLException, IOException {
        String table="companies".equals(resource)?"companies":"services".equals(resource)?"service_catalog":"service_orders";
        if ("companies".equals(resource) || "services".equals(resource)) {
            String column="companies".equals(resource)?"company_id":"service_id";
            try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT COUNT(*) FROM service_orders WHERE "+column+"=?")){ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){if(rs.next()&&rs.getLong(1)>0){sendJson(exchange,409,error("Este registro está vinculado a ordens de serviço. Altere ou exclua as ordens antes de excluí-lo."));return;}}}
        }
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("DELETE FROM "+table+" WHERE id=?")){ps.setLong(1,id);if(ps.executeUpdate()==0){sendJson(exchange,404,error("Registro não encontrado."));return;}}
        sendJson(exchange,200,Map.of("message","Registro excluído com sucesso."));
    }

    private static void handlePaymentApi(HttpExchange exchange, String method, String path) throws IOException, SQLException {
        if ("/api/payment-settings".equals(path)) {
            if ("GET".equals(method)) { sendJson(exchange, 200, readPaymentSettings()); return; }
            if ("PUT".equals(method)) { savePaymentSettings(exchange); return; }
            methodNotAllowed(exchange, "GET, PUT"); return;
        }
        if ("/api/pix/generate".equals(path)) {
            if (!"POST".equals(method)) { methodNotAllowed(exchange, "POST"); return; }
            generatePixForOrder(exchange); return;
        }
        if ("/api/pix/confirm".equals(path)) {
            if (!"POST".equals(method)) { methodNotAllowed(exchange, "POST"); return; }
            confirmPixPayment(exchange); return;
        }
        sendJson(exchange, 404, error("Recurso de pagamento não encontrado."));
    }

    private static Map<String,Object> readPaymentSettings() throws SQLException {
        Map<String,String> values = new HashMap<>();
        try (Connection c=connect(); Statement st=c.createStatement(); ResultSet rs=st.executeQuery("SELECT setting_key,setting_value FROM app_settings WHERE setting_key IN ('pix_key','pix_receiver_name','pix_city')")) {
            while(rs.next()) values.put(rs.getString(1),rs.getString(2));
        }
        String key=values.getOrDefault("pix_key",""); String name=values.getOrDefault("pix_receiver_name",""); String city=values.getOrDefault("pix_city","");
        Map<String,Object> out=new LinkedHashMap<>(); out.put("pixKey",key); out.put("receiverName",name); out.put("city",city); out.put("configured",!key.isBlank()&&!name.isBlank()&&!city.isBlank()); return out;
    }

    private static void savePaymentSettings(HttpExchange exchange) throws IOException, SQLException {
        JsonObject input=readJsonObject(exchange);
        String key=readString(input,"pixKey").trim(); String name=normalizePixText(readString(input,"receiverName").trim(),25); String city=normalizePixText(readString(input,"city").trim(),15);
        if(key.length()<3 || key.length()>77 || key.contains("\n") || key.contains("\r")) throw new IllegalArgumentException("Informe uma chave Pix válida (entre 3 e 77 caracteres). Não use senha bancária.");
        if(name.isBlank() || name.length()>25) throw new IllegalArgumentException("O nome do recebedor deve conter até 25 caracteres.");
        if(city.isBlank() || city.length()>15) throw new IllegalArgumentException("A cidade do recebedor deve conter até 15 caracteres.");
        String now=LocalDateTime.now().format(DATE_FORMAT);
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value, updated_at=excluded.updated_at")) {
            for(String[] pair:new String[][]{{"pix_key",key},{"pix_receiver_name",name},{"pix_city",city}}){ps.setString(1,pair[0]);ps.setString(2,pair[1]);ps.setString(3,now);ps.addBatch();}
            ps.executeBatch();
        }
        sendJson(exchange,200,Map.of("saved",true,"message","Configurações Pix salvas.","settings",readPaymentSettings()));
    }

    private static void generatePixForOrder(HttpExchange exchange) throws IOException, SQLException {
        JsonObject input=readJsonObject(exchange); long orderId;
        try { orderId=Long.parseLong(readString(input,"orderId").trim()); } catch(NumberFormatException ex){throw new IllegalArgumentException("Selecione uma ordem de serviço válida.");}
        if(orderId<=0) throw new IllegalArgumentException("Selecione uma ordem de serviço válida.");
        String orderTitle, customerName, status; BigDecimal amount;
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT o.title,o.status,o.price,COALESCE(c.name,co.name,'Cliente') AS customer_name FROM service_orders o LEFT JOIN customers c ON c.id=o.customer_id LEFT JOIN companies co ON co.id=o.company_id WHERE o.id=?")){
            ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){if(!rs.next()){sendJson(exchange,404,error("Ordem de serviço não encontrada."));return;}orderTitle=rs.getString("title");status=rs.getString("status");amount=rs.getBigDecimal("price");customerName=rs.getString("customer_name");}
        }
        if(!"Aguardando pagamento".equals(status)) throw new IllegalArgumentException("Para gerar a cobrança, revise o orçamento e mude o status da OS para ‘Aguardando pagamento’.");
        amount=amount==null?BigDecimal.ZERO:amount.setScale(2,RoundingMode.HALF_UP);
        if(amount.signum()<=0) throw new IllegalArgumentException("A ordem precisa ter um valor aprovado maior que zero.");
        @SuppressWarnings("unchecked") Map<String,Object> settings=readPaymentSettings();
        if(!Boolean.TRUE.equals(settings.get("configured"))) throw new IllegalArgumentException("Configure sua chave Pix, o nome do recebedor e a cidade em Financeiro → Configuração Pix antes de gerar cobranças.");
        String txid="OS"+String.format(Locale.ROOT,"%08d",orderId);
        String payload=buildPixPayload(String.valueOf(settings.get("pixKey")),String.valueOf(settings.get("receiverName")),String.valueOf(settings.get("city")),amount,txid);
        String image=generateQrImageBase64(payload);
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT status FROM pix_payments WHERE order_id=?")){ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){if(rs.next() && "Pago".equals(rs.getString("status")))throw new IllegalArgumentException("Esta OS já tem um pagamento marcado como recebido. Não gere uma nova cobrança para o mesmo serviço.");}}
        String now=LocalDateTime.now().format(DATE_FORMAT);
        String sql="INSERT INTO pix_payments(order_id,amount,txid,payload,status,created_at,paid_at) VALUES(?,?,?,?,'Pendente',?,'') ON CONFLICT(order_id) DO UPDATE SET amount=excluded.amount,txid=excluded.txid,payload=excluded.payload,status='Pendente',created_at=excluded.created_at,paid_at=''";
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement(sql)){ps.setLong(1,orderId);ps.setBigDecimal(2,amount);ps.setString(3,txid);ps.setString(4,payload);ps.setString(5,now);ps.executeUpdate();}
        Map<String,Object> out=new LinkedHashMap<>();out.put("orderId",orderId);out.put("requestNumber","OS-"+String.format("%05d",orderId));out.put("title",orderTitle);out.put("customerName",customerName);out.put("amount",amount);out.put("txid",txid);out.put("pixPayload",payload);out.put("qrPngBase64",image);out.put("paymentStatus","Pendente");
        sendJson(exchange,200,out);
    }

    private static void confirmPixPayment(HttpExchange exchange) throws IOException, SQLException {
        JsonObject input=readJsonObject(exchange);long orderId;
        try{orderId=Long.parseLong(readString(input,"orderId").trim());}catch(NumberFormatException ex){throw new IllegalArgumentException("Selecione uma ordem válida.");}
        String now=LocalDateTime.now().format(DATE_FORMAT);String date=java.time.LocalDate.now().toString();
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                BigDecimal amount;String txid;String status;
                try(PreparedStatement ps=c.prepareStatement("SELECT amount,txid,status FROM pix_payments WHERE order_id=?")){
                    ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Gere primeiro o QR Code desta ordem de serviço.");amount=rs.getBigDecimal("amount");txid=rs.getString("txid");status=rs.getString("status");}
                }
                if("Pago".equals(status))throw new IllegalArgumentException("Este pagamento já foi marcado como recebido.");
                try(PreparedStatement ps=c.prepareStatement("UPDATE pix_payments SET status='Pago',paid_at=? WHERE order_id=? AND status='Pendente'")){ps.setString(1,now);ps.setLong(2,orderId);if(ps.executeUpdate()!=1)throw new IllegalArgumentException("O status do pagamento mudou. Atualize a tela e confira o registro.");}
                JsonObject finance=new JsonObject();finance.addProperty("type","Receita");finance.addProperty("description","Pix recebido — OS-"+String.format("%05d",orderId));finance.addProperty("category","Serviços");finance.addProperty("amount",amount);finance.addProperty("dueDate",date);finance.addProperty("paymentDate",date);finance.addProperty("status","Pago");finance.addProperty("workOrderId",orderId);finance.addProperty("notes","Confirmação manual após conferência no Itaú. TXID: "+txid+". Confirme somente após verificar o crédito real.");
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO operational_records(module,payload,created_at,updated_at) VALUES('finance',?,?,?)")){ps.setString(1,GSON.toJson(finance));ps.setString(2,now);ps.setString(3,now);ps.executeUpdate();}
                c.commit();
            }catch(Exception ex){c.rollback();if(ex instanceof IllegalArgumentException iae)throw iae;if(ex instanceof SQLException sqle)throw sqle;throw new SQLException("Não foi possível registrar a confirmação do pagamento.",ex);}
            finally{c.setAutoCommit(true);}
        }
        sendJson(exchange,200,Map.of("paid",true,"message","Pagamento registrado no financeiro. Confirme que o crédito realmente entrou no Itaú antes de usar esta ação."));
    }

    private static String buildPixPayload(String key,String name,String city,BigDecimal amount,String txid) {
        String merchant=normalizePixText(name,25);String merchantCity=normalizePixText(city,15);
        if(key==null||key.isBlank())throw new IllegalArgumentException("Configure sua chave Pix.");
        String account=tlv("00","br.gov.bcb.pix")+tlv("01",key.trim());
        String additional=tlv("05",txid);
        String value=""+tlv("00","01")+tlv("01","11")+tlv("26",account)+tlv("52","0000")+tlv("53","986")+tlv("54",amount.setScale(2,RoundingMode.HALF_UP).toPlainString())+tlv("58","BR")+tlv("59",merchant)+tlv("60",merchantCity)+tlv("62",additional)+"6304";
        return value+String.format(Locale.ROOT,"%04X",crc16(value));
    }
    private static String tlv(String id,String value){return id+String.format(Locale.ROOT,"%02d",value.length())+value;}
    private static int crc16(String text){int crc=0xFFFF;for(byte b:text.getBytes(StandardCharsets.UTF_8)){crc^=(b&0xFF)<<8;for(int i=0;i<8;i++)crc=(crc&0x8000)!=0?((crc<<1)^0x1021):(crc<<1);crc&=0xFFFF;}return crc;}
    private static String normalizePixText(String value,int max){String normalized=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 .,&/-]"," ").replaceAll("\\s+"," ").trim();return normalized.length()>max?normalized.substring(0,max).trim():normalized;}
    private static String generateQrImageBase64(String payload){
        try{Map<EncodeHintType,Object> hints=Map.of(EncodeHintType.MARGIN,1,EncodeHintType.CHARACTER_SET,"UTF-8");BitMatrix matrix=new QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,360,360,hints);BufferedImage image=MatrixToImageWriter.toBufferedImage(matrix);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"PNG",out);return Base64.getEncoder().encodeToString(out.toByteArray());}
        catch(WriterException|IOException ex){throw new IllegalStateException("Não foi possível gerar a imagem do QR Code Pix.",ex);}
    }

    // Módulos operacionais (estoque, financeiro, agenda, garantias e relatórios) usam
    // registros JSON versionáveis em uma tabela persistente comum. Os nomes dos módulos
    // e campos são allow-listed; consultas e gravações usam parâmetros preparados.
    private static String operationsModuleFromPath(String path) {
        String[] modules = {"inventory", "stock-movements", "finance", "appointments", "warranties"};
        for (String module : modules) {
            String prefix = "/api/" + module;
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return module;
        }
        return null;
    }

    private static void handleOperationsApi(HttpExchange exchange, String method, String path) throws IOException, SQLException {
        String module = operationsModuleFromPath(path);
        if (module == null) { sendJson(exchange, 404, error("Módulo não encontrado.")); return; }
        String prefix = "/api/" + module;
        if (path.equals(prefix)) {
            switch (method) {
                case "GET" -> listOperationRecords(exchange, module);
                case "POST" -> {
                    if ("stock-movements".equals(module)) createStockMovement(exchange);
                    else saveOperationRecord(exchange, module, null);
                }
                default -> methodNotAllowed(exchange, "GET, POST");
            }
            return;
        }
        String idPart = path.substring(prefix.length() + 1);
        if (idPart.isBlank() || idPart.contains("/")) { sendJson(exchange, 404, error("Registro não encontrado.")); return; }
        long id;
        try { id = Long.parseLong(idPart); }
        catch (NumberFormatException e) { sendJson(exchange, 400, error("O identificador informado é inválido.")); return; }
        switch (method) {
            case "GET" -> getOperationRecord(exchange, module, id);
            case "PUT" -> {
                if ("stock-movements".equals(module)) sendJson(exchange, 405, error("Movimentações de estoque são registros históricos e não podem ser editadas."));
                else saveOperationRecord(exchange, module, id);
            }
            case "DELETE" -> {
                if ("stock-movements".equals(module)) sendJson(exchange, 405, error("Movimentações de estoque são registros históricos e não podem ser excluídas."));
                else deleteOperationRecord(exchange, module, id);
            }
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private static void listOperationRecords(HttpExchange exchange, String module) throws SQLException, IOException {
        List<JsonObject> records = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id, payload, created_at, updated_at FROM operational_records WHERE module=? ORDER BY id DESC")) {
            ps.setString(1, module);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) records.add(operationFrom(rs, module)); }
        }
        sendJson(exchange, 200, records);
    }

    private static void getOperationRecord(HttpExchange exchange, String module, long id) throws SQLException, IOException {
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id, payload, created_at, updated_at FROM operational_records WHERE module=? AND id=?")) {
            ps.setString(1, module); ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { sendJson(exchange, 404, error("Registro não encontrado.")); return; }
                sendJson(exchange, 200, operationFrom(rs, module));
            }
        }
    }

    private static JsonObject operationFrom(ResultSet rs, String module) throws SQLException {
        JsonObject result;
        try {
            JsonElement parsed = JsonParser.parseString(rs.getString("payload"));
            result = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) { result = new JsonObject(); }
        result.addProperty("id", rs.getLong("id"));
        result.addProperty("module", module);
        result.addProperty("createdAt", rs.getString("created_at"));
        result.addProperty("updatedAt", rs.getString("updated_at"));
        return result;
    }

    private static void saveOperationRecord(HttpExchange exchange, String module, Long id) throws IOException, SQLException {
        JsonObject input = readJsonObject(exchange);
        JsonObject payload = validateOperationPayload(module, input);
        // A quantidade só pode mudar por movimentação de estoque, para manter o histórico confiável.
        if ("inventory".equals(module) && id != null) {
            try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                    "SELECT payload FROM operational_records WHERE module='inventory' AND id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        JsonObject current = JsonParser.parseString(rs.getString("payload")).getAsJsonObject();
                        payload.add("quantity", current.has("quantity") ? current.get("quantity") : new com.google.gson.JsonPrimitive(0));
                    }
                }
            }
        }
        String now = LocalDateTime.now().format(DATE_FORMAT);
        String serialized = GSON.toJson(payload);
        if (id == null) {
            String sql = "INSERT INTO operational_records(module, payload, created_at, updated_at) VALUES(?,?,?,?)";
            long newId = insertWithId(sql, ps -> { ps.setString(1, module); ps.setString(2, serialized); ps.setString(3, now); ps.setString(4, now); });
            getOperationRecord(exchange, module, newId);
        } else {
            try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                    "UPDATE operational_records SET payload=?, updated_at=? WHERE module=? AND id=?")) {
                ps.setString(1, serialized); ps.setString(2, now); ps.setString(3, module); ps.setLong(4, id);
                if (ps.executeUpdate() == 0) { sendJson(exchange, 404, error("Registro não encontrado.")); return; }
            }
            getOperationRecord(exchange, module, id);
        }
    }

    private static JsonObject validateOperationPayload(String module, JsonObject input) throws SQLException {
        JsonObject out = new JsonObject();
        switch (module) {
            case "inventory" -> {
                out.addProperty("name", requiredText(input, "name", "Nome do item", 160));
                out.addProperty("sku", optionalText(input, "sku", "Código/SKU", 80));
                out.addProperty("category", optionalText(input, "category", "Categoria", 100));
                out.addProperty("quantity", nonNegativeInteger(input, "quantity", "Quantidade"));
                out.addProperty("minimumQuantity", nonNegativeInteger(input, "minimumQuantity", "Estoque mínimo"));
                out.addProperty("costPrice", decimalValue(input, "costPrice", "Custo unitário"));
                out.addProperty("salePrice", decimalValue(input, "salePrice", "Preço de referência"));
                out.addProperty("supplier", optionalText(input, "supplier", "Fornecedor", 160));
                out.addProperty("notes", optionalText(input, "notes", "Observações", 2000));
            }
            case "stock-movements" -> throw new IllegalArgumentException("Use a operação de movimentação de estoque.");
            case "finance" -> {
                String type = enumValue(input, "type", "Tipo", Set.of("Receita", "Despesa"), "Receita");
                out.addProperty("type", type);
                out.addProperty("description", requiredText(input, "description", "Descrição", 180));
                out.addProperty("category", optionalText(input, "category", "Categoria", 100));
                out.addProperty("amount", decimalValue(input, "amount", "Valor"));
                out.addProperty("dueDate", optionalDate(input, "dueDate", "Vencimento"));
                out.addProperty("paymentDate", optionalDate(input, "paymentDate", "Data de pagamento"));
                out.addProperty("status", enumValue(input, "status", "Status", Set.of("Pendente", "Pago", "Atrasado", "Cancelado"), "Pendente"));
                addOptionalRecordReference(out, input, "companyId", "companies", "empresa");
                addOptionalRecordReference(out, input, "customerId", "customers", "cliente/contratante");
                addOptionalRecordReference(out, input, "workOrderId", "service_orders", "ordem de serviço");
                out.addProperty("notes", optionalText(input, "notes", "Observações", 2000));
            }
            case "appointments" -> {
                out.addProperty("title", requiredText(input, "title", "Título do compromisso", 180));
                out.addProperty("type", enumValue(input, "type", "Tipo", Set.of("Visita técnica", "Retorno", "Manutenção preventiva", "Atendimento remoto", "Entrega/retirada", "Outro"), "Visita técnica"));
                String scheduledDateTime = requiredText(input, "scheduledDateTime", "Data e hora", 25);
                try { java.time.LocalDateTime.parse(scheduledDateTime); }
                catch (java.time.format.DateTimeParseException ex) { throw new IllegalArgumentException("Informe uma data e hora válidas para o agendamento."); }
                out.addProperty("scheduledDateTime", scheduledDateTime);
                addOptionalRecordReference(out, input, "companyId", "companies", "empresa");
                addOptionalRecordReference(out, input, "customerId", "customers", "cliente");
                addOptionalRecordReference(out, input, "workOrderId", "service_orders", "ordem de serviço");
                out.addProperty("technician", optionalText(input, "technician", "Técnico responsável", 120));
                out.addProperty("status", enumValue(input, "status", "Status", Set.of("Agendado", "Concluído", "Cancelado"), "Agendado"));
                out.addProperty("notes", optionalText(input, "notes", "Observações", 2000));
            }
            case "warranties" -> {
                out.addProperty("itemOrService", requiredText(input, "itemOrService", "Serviço/equipamento", 180));
                addOptionalRecordReference(out, input, "companyId", "companies", "empresa");
                addOptionalRecordReference(out, input, "customerId", "customers", "cliente");
                addOptionalRecordReference(out, input, "workOrderId", "service_orders", "ordem de serviço");
                String start = optionalDate(input, "startDate", "Início da garantia");
                String end = optionalDate(input, "endDate", "Fim da garantia");
                if (!start.isBlank() && !end.isBlank() && end.compareTo(start) < 0) throw new IllegalArgumentException("O fim da garantia não pode ser anterior ao início.");
                out.addProperty("startDate", start);
                out.addProperty("endDate", end);
                out.addProperty("status", enumValue(input, "status", "Status", Set.of("Vigente", "Expirada", "Acionada", "Encerrada"), "Vigente"));
                out.addProperty("terms", optionalText(input, "terms", "Termos da garantia", 1600));
                out.addProperty("notes", optionalText(input, "notes", "Observações", 2000));
            }
            default -> throw new IllegalArgumentException("Módulo operacional inválido.");
        }
        return out;
    }

    private static String requiredText(JsonObject input, String key, String label, int max) {
        String value = readString(input, key).trim();
        if (value.isBlank()) throw new IllegalArgumentException(label + " é obrigatório.");
        if (value.length() > max) throw new IllegalArgumentException(label + " deve ter no máximo " + max + " caracteres.");
        return value;
    }

    private static String optionalText(JsonObject input, String key, String label, int max) {
        String value = readString(input, key).trim();
        if (value.length() > max) throw new IllegalArgumentException(label + " deve ter no máximo " + max + " caracteres.");
        return value;
    }

    private static String enumValue(JsonObject input, String key, String label, Set<String> options, String defaultValue) {
        String value = readString(input, key).trim();
        if (value.isBlank()) value = defaultValue;
        if (!options.contains(value)) throw new IllegalArgumentException("Selecione um valor válido para " + label.toLowerCase() + ".");
        return value;
    }

    private static String optionalDate(JsonObject input, String key, String label) {
        String value = readString(input, key).trim();
        if (value.isBlank()) return "";
        try { java.time.LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException ex) { throw new IllegalArgumentException("Informe " + label.toLowerCase() + " no formato de data correto."); }
        return value;
    }

    private static int nonNegativeInteger(JsonObject input, String key, String label) {
        String value = readString(input, key).trim();
        if (value.isBlank()) return 0;
        try { int number = Integer.parseInt(value); if (number < 0) throw new NumberFormatException(); return number; }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(label + " deve ser um número inteiro maior ou igual a zero."); }
    }

    private static java.math.BigDecimal decimalValue(JsonObject input, String key, String label) {
        String value = readString(input, key).trim();
        if (value.isBlank()) return java.math.BigDecimal.ZERO;
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(value.replace("R$", "").replace(" ", "").replace(",", "."));
            if (number.signum() < 0) throw new NumberFormatException();
            return number.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) { throw new IllegalArgumentException(label + " deve ser um valor válido e não negativo."); }
    }

    private static void addOptionalRecordReference(JsonObject target, JsonObject input, String key, String table, String label) throws SQLException {
        String raw = readString(input, key).trim();
        if (raw.isBlank()) { target.add(key, com.google.gson.JsonNull.INSTANCE); return; }
        long id;
        try { id = Long.parseLong(raw); if (id <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Selecione uma referência válida para " + label + "."); }
        if (!operationalRecordExists(table, id)) throw new IllegalArgumentException("A referência selecionada para " + label + " não existe.");
        target.addProperty(key, id);
    }

    private static boolean operationalRecordExists(String table, long id) throws SQLException {
        if (!Set.of("companies", "customers", "service_catalog", "service_orders").contains(table)) return false;
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id=?")) {
            ps.setLong(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static void createStockMovement(HttpExchange exchange) throws IOException, SQLException {
        JsonObject input = readJsonObject(exchange);
        String rawId = readString(input, "inventoryId").trim();
        long itemId;
        try { itemId = Long.parseLong(rawId); if (itemId <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Selecione um item de estoque válido."); }
        String kind = enumValue(input, "kind", "Tipo de movimentação", Set.of("Entrada", "Saída"), "Entrada");
        int quantity = nonNegativeInteger(input, "quantity", "Quantidade movimentada");
        if (quantity <= 0) throw new IllegalArgumentException("A quantidade movimentada deve ser maior que zero.");
        String reason = requiredText(input, "reason", "Motivo", 240);
        String now = LocalDateTime.now().format(DATE_FORMAT);
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                JsonObject inventory;
                try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM operational_records WHERE module='inventory' AND id=?")) {
                    ps.setLong(1, itemId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("O item de estoque selecionado não existe.");
                        inventory = JsonParser.parseString(rs.getString("payload")).getAsJsonObject();
                    }
                }
                int current = inventory.has("quantity") ? inventory.get("quantity").getAsInt() : 0;
                int updated = "Entrada".equals(kind) ? Math.addExact(current, quantity) : current - quantity;
                if (updated < 0) throw new IllegalArgumentException("Não há estoque suficiente para esta saída.");
                inventory.addProperty("quantity", updated);
                try (PreparedStatement ps = c.prepareStatement("UPDATE operational_records SET payload=?, updated_at=? WHERE module='inventory' AND id=?")) {
                    ps.setString(1, GSON.toJson(inventory)); ps.setString(2, now); ps.setLong(3, itemId); ps.executeUpdate();
                }
                JsonObject movement = new JsonObject();
                movement.addProperty("inventoryId", itemId);
                movement.addProperty("inventoryName", inventory.has("name") ? inventory.get("name").getAsString() : "Item de estoque");
                movement.addProperty("kind", kind); movement.addProperty("quantity", quantity); movement.addProperty("reason", reason);
                String sql = "INSERT INTO operational_records(module, payload, created_at, updated_at) VALUES(?,?,?,?)";
                try (PreparedStatement ps = c.prepareStatement(DATABASE.postgres ? sql + " RETURNING id" : sql)) {
                    ps.setString(1, "stock-movements"); ps.setString(2, GSON.toJson(movement)); ps.setString(3, now); ps.setString(4, now);
                    if (DATABASE.postgres) { try (ResultSet rs = ps.executeQuery()) { if (rs.next()) movement.addProperty("id", rs.getLong(1)); } }
                    else { ps.executeUpdate(); try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) { if (rs.next()) movement.addProperty("id", rs.getLong(1)); } }
                }
                movement.addProperty("module", "stock-movements"); movement.addProperty("createdAt", now); movement.addProperty("updatedAt", now);
                c.commit(); sendJson(exchange, 201, movement);
            } catch (Exception ex) {
                c.rollback();
                if (ex instanceof IllegalArgumentException iae) throw iae;
                if (ex instanceof SQLException sqle) throw sqle;
                throw new SQLException("Não foi possível registrar a movimentação de estoque.", ex);
            } finally { c.setAutoCommit(true); }
        }
    }

    private static void deleteOperationRecord(HttpExchange exchange, String module, long id) throws SQLException, IOException {
        if ("inventory".equals(module)) {
            try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                    "SELECT payload FROM operational_records WHERE module='stock-movements'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            JsonObject move = JsonParser.parseString(rs.getString("payload")).getAsJsonObject();
                            if (move.has("inventoryId") && move.get("inventoryId").getAsLong() == id) {
                                sendJson(exchange, 409, error("Este item possui movimentações no histórico e não pode ser excluído. Mantenha o registro para preservar a auditoria."));
                                return;
                            }
                        } catch (RuntimeException ignored) { /* Ignora apenas registros históricos antigos inválidos. */ }
                    }
                }
            }
        }
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM operational_records WHERE module=? AND id=?")) {
            ps.setString(1, module); ps.setLong(2, id);
            if (ps.executeUpdate() == 0) { sendJson(exchange, 404, error("Registro não encontrado.")); return; }
        }
        sendJson(exchange, 200, Map.of("message", "Registro excluído com sucesso."));
    }

    private static long insertWithId(String sql, SqlBinder binder) throws SQLException {
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement(DATABASE.postgres?sql+" RETURNING id":sql)){
            binder.bind(ps);
            if(DATABASE.postgres){try(ResultSet rs=ps.executeQuery()){if(rs.next())return rs.getLong(1);}}
            else {ps.executeUpdate();try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT last_insert_rowid()")){if(rs.next())return rs.getLong(1);}}
        }
        throw new SQLException("Não foi possível recuperar o identificador criado.");
    }

    private static BigDecimal parseMoney(String raw, String field) {
        if(raw==null||raw.isBlank())return BigDecimal.ZERO;
        try{return new BigDecimal(raw.trim().replace("R$","").replace(" ","").replace(",","."));}
        catch(NumberFormatException e){throw new IllegalArgumentException("Informe um valor válido para "+field.toLowerCase()+".");}
    }

    private static Long parseOptionalLong(String value, String field) {
        if(value==null||value.isBlank()||"null".equalsIgnoreCase(value))return null;
        try{long parsed=Long.parseLong(value);if(parsed<=0)throw new NumberFormatException();return parsed;}
        catch(NumberFormatException e){throw new IllegalArgumentException("Selecione um "+field.toLowerCase()+" válido.");}
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if(value==null)ps.setNull(index,java.sql.Types.BIGINT);else ps.setLong(index,value);
    }

    private static boolean recordExists(String table,long id) throws SQLException {
        if(!Set.of("companies","customers","service_catalog").contains(table))return false;
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT 1 FROM "+table+" WHERE id=?")){ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next();}}
    }

    private static Map<String,Object> getCompanyMap(long id) throws SQLException {
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT * FROM companies WHERE id=?")){ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?companyFrom(rs):Map.of();}}
    }
    private static Map<String,Object> getServiceMap(long id) throws SQLException {
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT * FROM service_catalog WHERE id=?")){ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?serviceFrom(rs):Map.of();}}
    }
    private static Map<String,Object> getWorkOrderMap(long id) throws SQLException {
        String sql="SELECT o.*,co.name AS company_name,co.trade_name AS company_trade_name,cu.name AS customer_name,sv.name AS service_name FROM service_orders o LEFT JOIN companies co ON co.id=o.company_id LEFT JOIN customers cu ON cu.id=o.customer_id LEFT JOIN service_catalog sv ON sv.id=o.service_id WHERE o.id=?";
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement(sql)){ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?workOrderFrom(rs):Map.of();}}
    }

    private static Map<String,Object> companyFrom(ResultSet rs) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getLong("id"));m.put("name",rs.getString("name"));m.put("tradeName",rs.getString("trade_name"));m.put("cnpj",rs.getString("cnpj"));m.put("email",rs.getString("email"));m.put("phone",rs.getString("phone"));m.put("contactName",rs.getString("contact_name"));m.put("address",rs.getString("address"));m.put("city",rs.getString("city"));m.put("state",rs.getString("state"));m.put("status",rs.getString("status"));m.put("notes",rs.getString("notes"));m.put("createdAt",rs.getString("created_at"));m.put("updatedAt",rs.getString("updated_at"));return m;
    }
    private static Map<String,Object> serviceFrom(ResultSet rs) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getLong("id"));m.put("name",rs.getString("name"));m.put("category",rs.getString("category"));m.put("description",rs.getString("description"));m.put("defaultPrice",rs.getBigDecimal("default_price"));m.put("minPrice",rs.getBigDecimal("public_min_price"));m.put("maxPrice",rs.getBigDecimal("public_max_price"));m.put("pricingUnit",rs.getString("pricing_unit"));m.put("publicEnabled",rs.getInt("public_enabled")==1);m.put("estimatedHours",rs.getBigDecimal("estimated_hours"));m.put("status",rs.getString("status"));m.put("createdAt",rs.getString("created_at"));m.put("updatedAt",rs.getString("updated_at"));return m;
    }
    private static Map<String,Object> workOrderFrom(ResultSet rs) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getLong("id"));long companyId=rs.getLong("company_id");m.put("companyId",rs.wasNull()?null:companyId);long customerId=rs.getLong("customer_id");m.put("customerId",rs.wasNull()?null:customerId);long serviceId=rs.getLong("service_id");m.put("serviceId",rs.wasNull()?null:serviceId);m.put("companyName",rs.getString("company_name"));m.put("companyTradeName",rs.getString("company_trade_name"));m.put("customerName",rs.getString("customer_name"));m.put("serviceName",rs.getString("service_name"));m.put("title",rs.getString("title"));m.put("serviceType",rs.getString("service_type"));m.put("description",rs.getString("description"));m.put("status",rs.getString("status"));m.put("priority",rs.getString("priority"));m.put("technician",rs.getString("technician"));m.put("scheduledDate",rs.getString("scheduled_date"));m.put("completedDate",rs.getString("completed_date"));m.put("price",rs.getBigDecimal("price"));m.put("notes",rs.getString("notes"));m.put("createdAt",rs.getString("created_at"));m.put("updatedAt",rs.getString("updated_at"));return m;
    }

    @FunctionalInterface private interface SqlBinder { void bind(PreparedStatement statement) throws SQLException; }

    private static CustomerInput readCustomerInput(HttpExchange exchange) throws IOException {
        JsonObject object = readJsonObject(exchange);
        String name = readString(object, "name").trim();
        String email = readString(object, "email").trim();
        String phone = readString(object, "phone").trim();
        String cpf = readString(object, "cpf").trim();
        String birthDate = readString(object, "birthDate").trim();
        String company = readString(object, "company").trim();
        String status = readString(object, "status").trim();
        String address = readString(object, "address").trim();
        String neighborhood = readString(object, "neighborhood").trim();
        String city = readString(object, "city").trim();
        String state = readString(object, "state").trim().toUpperCase();
        String zipCode = readString(object, "zipCode").trim();
        String notes = readString(object, "notes").trim();
        String personType = readString(object, "personType").trim();
        String recordType = readString(object, "recordType").trim();
        String tradeName = readString(object, "tradeName").trim();
        String contactName = readString(object, "contactName").trim();
        String serviceType = readString(object, "serviceType").trim();
        if (personType.isBlank()) personType = "PF";
        if (recordType.isBlank()) recordType = "Cliente avulso";
        if (tradeName.isBlank()) tradeName = company;
        if (company.isBlank()) company = tradeName;
        if (serviceType.isBlank()) serviceType = "Assistência técnica / manutenção";
        if (!Set.of("PF", "PJ").contains(personType)) throw new IllegalArgumentException("Selecione Pessoa Física ou Pessoa Jurídica.");
        if (!Set.of("Cliente avulso", "Empresa contratante").contains(recordType)) throw new IllegalArgumentException("Selecione um tipo de relacionamento válido.");
        if (!Set.of("Assistência técnica / manutenção", "Software e sistemas", "Redes e infraestrutura", "Suporte técnico", "Consultoria", "Outro").contains(serviceType)) throw new IllegalArgumentException("Selecione uma área de atendimento válida.");
        if (personType.equals("PJ")) birthDate = "";
        if (name.isBlank()) throw new IllegalArgumentException("O nome da pessoa ou organização é obrigatório.");
        if (name.length() > 120) throw new IllegalArgumentException("O nome deve ter no máximo 120 caracteres.");
        if (!email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Informe um e-mail válido.");
        if (birthDate.length() > 0) {
            try { java.time.LocalDate.parse(birthDate); }
            catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Informe a data de nascimento corretamente."); }
        }
        if (status.isBlank()) status = "Ativo";
        if (!Set.of("Ativo", "Pendente", "Inativo").contains(status)) throw new IllegalArgumentException("Selecione um status válido.");
        if (email.length() > 160 || phone.length() > 40 || cpf.length() > 18 || birthDate.length() > 10
                || company.length() > 120 || address.length() > 180 || neighborhood.length() > 100
                || city.length() > 100 || state.length() > 2 || zipCode.length() > 12 || notes.length() > 2000
                || tradeName.length() > 160 || contactName.length() > 120) {
            throw new IllegalArgumentException("Um dos campos ultrapassou o limite de caracteres.");
        }
        return new CustomerInput(name, email, phone, cpf, birthDate, company, status, address, neighborhood, city, state, zipCode, notes, personType, recordType, tradeName, contactName, serviceType);
    }

    private static JsonObject readJsonObject(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(65537);
        if (bytes.length > 65536) throw new IllegalArgumentException("A requisição é muito grande.");
        JsonElement parsed;
        try { parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("O corpo enviado não contém um JSON válido."); }
        if (parsed == null || !parsed.isJsonObject()) throw new IllegalArgumentException("Envie os dados em formato JSON.");
        return parsed.getAsJsonObject();
    }

    private static String readString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static void bindInput(PreparedStatement statement, CustomerInput input) throws SQLException {
        statement.setString(1, input.name());
        statement.setString(2, input.email());
        statement.setString(3, input.phone());
        statement.setString(4, input.cpf());
        statement.setString(5, input.birthDate());
        statement.setString(6, input.company());
        statement.setString(7, input.status());
        statement.setString(8, input.address());
        statement.setString(9, input.neighborhood());
        statement.setString(10, input.city());
        statement.setString(11, input.state());
        statement.setString(12, input.zipCode());
        statement.setString(13, input.notes());
    }

    private static void bindExtendedCustomerInput(PreparedStatement statement, CustomerInput input) throws SQLException {
        statement.setString(14, input.personType());
        statement.setString(15, input.recordType());
        statement.setString(16, input.tradeName());
        statement.setString(17, input.contactName());
        statement.setString(18, input.serviceType());
    }

    private static Map<String, Object> getCustomerMap(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, email, phone, cpf, birth_date, company, status, address, neighborhood, city, state, zip_code, notes, person_type, record_type, trade_name, contact_name, service_type, legacy_company_id, created_at, updated_at FROM customers WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? customerFrom(rs) : Map.of(); }
        }
    }

    private static Map<String, Object> customerFrom(ResultSet rs) throws SQLException {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", rs.getLong("id"));
        customer.put("name", rs.getString("name"));
        customer.put("email", rs.getString("email"));
        customer.put("phone", rs.getString("phone"));
        customer.put("cpf", rs.getString("cpf"));
        customer.put("birthDate", rs.getString("birth_date"));
        customer.put("company", rs.getString("company"));
        customer.put("status", rs.getString("status"));
        customer.put("address", rs.getString("address"));
        customer.put("neighborhood", rs.getString("neighborhood"));
        customer.put("city", rs.getString("city"));
        customer.put("state", rs.getString("state"));
        customer.put("zipCode", rs.getString("zip_code"));
        customer.put("notes", rs.getString("notes"));
        customer.put("personType", rs.getString("person_type"));
        customer.put("recordType", rs.getString("record_type"));
        customer.put("tradeName", rs.getString("trade_name"));
        customer.put("contactName", rs.getString("contact_name"));
        customer.put("serviceType", rs.getString("service_type"));
        long legacyCompanyId = rs.getLong("legacy_company_id");
        customer.put("legacyCompanyId", rs.wasNull() ? null : legacyCompanyId);
        customer.put("createdAt", rs.getString("created_at"));
        customer.put("updatedAt", rs.getString("updated_at"));
        return customer;
    }

    private static Session getSession(HttpExchange exchange) {
        String sessionId = readCookie(exchange, "APPSESSION");
        if (sessionId == null) return null;
        Session session = SESSIONS.get(sessionId);
        if (session == null) return null;
        long now = System.currentTimeMillis();
        if (now - session.lastAccess > SESSION_TTL_MS) {
            SESSIONS.remove(sessionId);
            return null;
        }
        session.lastAccess = now;
        if (SESSIONS.size() > 5000) SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().lastAccess > SESSION_TTL_MS);
        return session;
    }

    private static Session authenticatedSession(HttpExchange exchange) {
        Session session = getSession(exchange);
        return session != null && session.username != null ? session : null;
    }

    private static Session createSession(HttpExchange exchange, String username) {
        String id = randomToken(32);
        String csrf = randomToken(32);
        Session session = new Session(id, csrf, username, System.currentTimeMillis());
        SESSIONS.put(id, session);
        setSessionCookie(exchange, id);
        return session;
    }

    private static boolean validCsrf(HttpExchange exchange, Session session) {
        String supplied = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (supplied == null || supplied.isBlank()) return false;
        return MessageDigest.isEqual(session.csrfToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String readCookie(HttpExchange exchange, String name) {
        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null) return null;
        for (String header : cookieHeaders) {
            for (String pair : header.split(";")) {
                String[] parts = pair.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals(name)) return parts[1];
            }
        }
        return null;
    }

    private static void setSessionCookie(HttpExchange exchange, String sessionId) {
        StringBuilder cookie = new StringBuilder("APPSESSION=").append(sessionId)
                .append("; Path=/; Max-Age=").append(SESSION_TTL_MS / 1000)
                .append("; HttpOnly; SameSite=Strict");
        boolean secure = Boolean.parseBoolean(System.getenv().getOrDefault("COOKIE_SECURE", "false"))
                || "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        if (secure) cookie.append("; Secure");
        exchange.getResponseHeaders().add("Set-Cookie", cookie.toString());
    }

    private static void clearSessionCookie(HttpExchange exchange) {
        boolean secure = Boolean.parseBoolean(System.getenv().getOrDefault("COOKIE_SECURE", "false"))
                || "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        String value = "APPSESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", value);
    }

    private static String findPasswordHash(String username) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT password_hash FROM app_users WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        int iterations = 310_000;
        byte[] hash = derivePassword(password.toCharArray(), salt, iterations);
        return "pbkdf2-sha256$" + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    private static boolean verifyPassword(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$", 4);
            if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 1_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derivePassword(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) { return false; }
    }

    private static byte[] derivePassword(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("PBKDF2 indisponível nesta JVM.", e); }
        finally { spec.clearPassword(); java.util.Arrays.fill(password, '\0'); }
    }

    private static boolean isLoginBlocked(HttpExchange exchange) {
        String key = clientKey(exchange);
        LoginAttempt attempt = LOGIN_ATTEMPTS.get(key);
        if (attempt == null) return false;
        synchronized (attempt) {
            long now = System.currentTimeMillis();
            if (now - attempt.windowStarted >= LOGIN_WINDOW_MS) {
                LOGIN_ATTEMPTS.remove(key, attempt);
                return false;
            }
            return attempt.failures >= MAX_LOGIN_FAILURES;
        }
    }

    private static void registerFailedLogin(HttpExchange exchange) {
        String key = clientKey(exchange);
        long now = System.currentTimeMillis();
        LOGIN_ATTEMPTS.compute(key, (ignored, old) -> {
            if (old == null || now - old.windowStarted >= LOGIN_WINDOW_MS) return new LoginAttempt(1, now);
            synchronized (old) { old.failures++; return old; }
        });
    }

    private static String clientKey(HttpExchange exchange) {
        // Em hospedagem atrás de proxy, usa o IP encaminhado pela plataforma quando disponível.
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
        return exchange.getRemoteAddress() == null ? "unknown" : exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String getQueryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (key.equals(name)) return parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8).trim() : "";
        }
        return "";
    }

    private static void serveStatic(HttpExchange exchange, String path) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET, HEAD");
            return;
        }
        String resource = switch (path) {
            case "/", "/solicitar", "/solicitar.html" -> "public/portal.html";
            case "/painel", "/admin", "/index.html" -> "public/index.html";
            case "/styles.css" -> "public/styles.css";
            case "/app.js" -> "public/app.js";
            case "/portal.css" -> "public/portal.css";
            case "/portal.js" -> "public/portal.js";
            default -> null;
        };
        if (resource == null) {
            sendBytes(exchange, 404, "text/plain; charset=utf-8", "Página não encontrada.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream input = App.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                sendBytes(exchange, 500, "text/plain; charset=utf-8", "Arquivo da interface não encontrado no pacote.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] content = input.readAllBytes();
            String type = resource.endsWith(".css") ? "text/css; charset=utf-8"
                    : resource.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : "text/html; charset=utf-8";
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Type", type);
                setSecurityHeaders(exchange.getResponseHeaders());
                exchange.sendResponseHeaders(200, -1);
            } else sendBytes(exchange, 200, type, content);
        }
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendJson(exchange, 405, error("Método não permitido."));
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message == null ? "Erro desconhecido." : message);
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        setSecurityHeaders(headers);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    }

    private static void setSecurityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
    }

    private record CustomerInput(String name, String email, String phone, String cpf, String birthDate, String company, String status, String address, String neighborhood, String city, String state, String zipCode, String notes, String personType, String recordType, String tradeName, String contactName, String serviceType) { }

    private static final class Session {
        final String id;
        final String csrfToken;
        final String username;
        volatile long lastAccess;
        Session(String id, String csrfToken, String username, long lastAccess) {
            this.id = id; this.csrfToken = csrfToken; this.username = username; this.lastAccess = lastAccess;
        }
    }

    private static final class LoginAttempt {
        int failures;
        final long windowStarted;
        LoginAttempt(int failures, long windowStarted) { this.failures = failures; this.windowStarted = windowStarted; }
    }

    private static final class PublicRequestAttempt {
        int requests;
        final long windowStarted;
        PublicRequestAttempt(int requests, long windowStarted) { this.requests = requests; this.windowStarted = windowStarted; }
    }

    private static final class DatabaseConfig {
        final boolean postgres;
        final String jdbcUrl;
        final String username;
        final String password;
        final Path sqlitePath;

        private DatabaseConfig(boolean postgres, String jdbcUrl, String username, String password, Path sqlitePath) {
            this.postgres = postgres; this.jdbcUrl = jdbcUrl; this.username = username; this.password = password; this.sqlitePath = sqlitePath;
        }

        static DatabaseConfig fromEnvironment() {
            String raw = System.getenv("DATABASE_URL");
            if (raw == null || raw.isBlank()) {
                Path path = Path.of(System.getenv().getOrDefault("SQLITE_PATH", "data/clientes.db")).toAbsolutePath();
                return new DatabaseConfig(false, "jdbc:sqlite:" + path, null, null, path);
            }
            raw = raw.trim();
            if (raw.startsWith("jdbc:sqlite:")) return new DatabaseConfig(false, raw, null, null, Path.of(raw.substring("jdbc:sqlite:".length())).toAbsolutePath());
            String user = System.getenv("DB_USER");
            String pass = System.getenv("DB_PASSWORD");
            if (raw.startsWith("jdbc:postgresql://")) {
                return new DatabaseConfig(true, raw, user, pass, null);
            }
            if (raw.startsWith("postgres://") || raw.startsWith("postgresql://")) {
                URI uri = URI.create(raw);
                String authorityAndPath = raw.substring(raw.indexOf("://") + 3);
                int at = authorityAndPath.lastIndexOf('@');
                if (at >= 0) {
                    String userInfo = authorityAndPath.substring(0, at);
                    authorityAndPath = authorityAndPath.substring(at + 1);
                    int colon = userInfo.indexOf(':');
                    if (user == null || user.isBlank()) user = decodeUriPart(colon >= 0 ? userInfo.substring(0, colon) : userInfo);
                    if ((pass == null || pass.isBlank()) && colon >= 0) pass = decodeUriPart(userInfo.substring(colon + 1));
                }
                String jdbc = "jdbc:postgresql://" + authorityAndPath;
                return new DatabaseConfig(true, jdbc, user, pass, null);
            }
            throw new IllegalStateException("DATABASE_URL deve ser uma URL PostgreSQL (postgresql://...) ou JDBC PostgreSQL.");
        }

        private static String decodeUriPart(String value) {
            // Em URLs, '+' pode ser literal; preserva-o ao decodificar escapes percentuais.
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
    }
}
