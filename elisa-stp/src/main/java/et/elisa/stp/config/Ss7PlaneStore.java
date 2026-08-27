package et.elisa.stp.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

/**
 * Persists the two editable SS7 planes — {@code outer} (upper STP / HLR / MSC)
 * and {@code inner} (GMLC / USSD / SMSC / OTA / silent-auth) — as standalone
 * fragments and merges them into the single {@code configs/ss7.json} the
 * ra-jss7 stack actually loads and applies.
 *
 * <p>Each plane is an independent JSON document of the same shape as
 * {@code configs/ss7.json} but carrying only its own {@code sctp.links},
 * {@code m3ua.as/routes}, {@code sccp.localPoints/routing} and
 * {@code services}. A save of one plane re-derives the merged document from the
 * two fragments and validates it with {@link Ss7ConfigLoader} before it is
 * written — so a broken fusion can never overwrite the working stack file.</p>
 *
 * <p>{@code sccp.localPoints} and {@code sccp.routing} entries carry
 * {@code networkId} (the jSS7 routing-table segregation key) plus an optional
 * {@code tenantId} extension field for per-provider tenant mapping; GT rules
 * ({@code match.gt} → {@code to.pc/ssn}) describe N-N relay between any two
 * nodes, ref. {@code NnRelayConfigTest}.</p>
 */
@ApplicationScoped
public class Ss7PlaneStore {
    private static final Logger LOG = LogManager.getLogger(Ss7PlaneStore.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // let operators annotate plane fragments: // line and # line comments
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_YAML_COMMENTS);

    public static final String PLANE_OUTER = "outer";
    public static final String PLANE_INNER = "inner";
    public static final String OUTER_FILE = "configs/ss7-outer.json";
    public static final String INNER_FILE = "configs/ss7-inner.json";
    public static final String MERGED_FILE = "configs/ss7.json";

    public record Result(boolean ok, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    /**
     * One operator-facing connection of a plane: the SCTP link plus its M3UA AS,
     * route and (optional) GTT rule — the unit the dashboard renders as a single
     * editable card with its own Save button.
     *
     * @param networkId jSS7 routing-table segregation key carried on the GTT rule
     *                  (must have a matching {@code sccp.localPoints} entry in the
     *                  stack — the outer plane owns the local points)
     */
    public record ConnectionSpec(String name, String channel, String localHost, int localPort,
                                 String peerHost, int peerPort, String asName, String asMode,
                                 int routingContext, int dpc, Integer opc, int networkId,
                                 String gttPattern, Integer gttToSsn, String oldName) {

        public static ConnectionSpec of(String name, String localHost, int localPort,
                                        String peerHost, int peerPort, String asMode,
                                        int routingContext, int dpc, int networkId,
                                        String gttPattern, Integer gttToSsn) {
            return new ConnectionSpec(name, "sctp", localHost, localPort, peerHost, peerPort,
                    "AS-" + name, asMode == null || asMode.isBlank() ? "loadshare" : asMode,
                    routingContext, dpc, null, networkId, gttPattern, gttToSsn, null);
        }
    }

    private final String outerFile;
    private final String innerFile;
    private final String mergedFile;

    /** Production bean: CWD-relative config paths. */
    public Ss7PlaneStore() {
        this(OUTER_FILE, INNER_FILE, MERGED_FILE);
    }

    /** Test / alternate-layout constructor with explicit plane file paths. */
    public Ss7PlaneStore(String outerFile, String innerFile, String mergedFile) {
        this.outerFile = outerFile;
        this.innerFile = innerFile;
        this.mergedFile = mergedFile;
    }

    /** Absolute path of the derived, single stack document. */
    public Path mergedPath() {
        return Path.of(mergedFile).toAbsolutePath().normalize();
    }

    /**
     * Returns the current source of one plane. First read serves a seed: the
     * outer plane is seeded from the existing {@code configs/ss7.json} (so the
     * current working topology is never lost), the inner plane from an
     * empty-but-shaped fragment the operator fills in.
     */
    public String plane(String plane) {
        String p = normalize(plane);
        Path file = fileFor(p);
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.warn("cannot read {}: {}", file, e.toString());
            }
        }
        if (PLANE_OUTER.equals(p) && Files.isRegularFile(mergedPath())) {
            try {
                return Files.readString(mergedPath(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.warn("cannot seed outer from {}: {}", mergedPath(), e.toString());
            }
        }
        return skeleton();
    }

    private Path fileFor(String plane) {
        return Path.of(PLANE_OUTER.equals(plane) ? outerFile : innerFile);
    }

    /**
     * Validates, persists and merges one plane. The submitted fragment must be a
     * JSON object; the fusion of the two planes must parse with
     * {@link Ss7ConfigLoader} before either file is touched.
     */
    public Result savePlane(String plane, String json) {
        String p = normalize(plane);
        if (!PLANE_OUTER.equals(p) && !PLANE_INNER.equals(p)) {
            return Result.fail("unknown plane '" + plane + "' (outer|inner)");
        }
        if (json == null || json.isBlank()) {
            return Result.fail("empty config");
        }
        JsonNode fragment;
        try {
            fragment = JSON.readTree(json);
        } catch (Exception e) {
            return Result.fail("invalid JSON: " + e.getMessage());
        }
        if (fragment == null || !fragment.isObject()) {
            return Result.fail("config must be a JSON object");
        }

        String outer = PLANE_OUTER.equals(p) ? json : plane(PLANE_OUTER);
        String inner = PLANE_INNER.equals(p) ? json : plane(PLANE_INNER);
        String merged = merge(outer, inner);

        String dup = findDuplicateNames(merged);
        if (dup != null) {
            return Result.fail(dup);
        }

        try {
            Ss7ConfigLoader.parse(merged);
        } catch (RuntimeException e) {
            return Result.fail("merged configs/ss7.json invalid: " + e.getMessage());
        }

        try {
            write(fileFor(p), fragment);
            write(mergedPath(), merged);
        } catch (Exception e) {
            return Result.fail("cannot persist: " + e.getMessage());
        }
        LOG.info("saved configs/ss7-{}.json -> {}.json", p,
                mergedPath().getFileName());
        return Result.ok("saved " + p + " plane; merged into " + MERGED_FILE);
    }

    /**
     * Fuses the two plane fragments into one stack document. Transport scalars
     * ({@code sctp.backend/mode/dataplane/...}) come from the outer plane as the
     * stack-global source of truth; every topology array is concatenated
     * outer-first, inner-second.
     */
    public static String merge(String outerJson, String innerJson) {
        ObjectNode outer = asObject(outerJson);
        ObjectNode inner = asObject(innerJson);

        ObjectNode m = JSON.createObjectNode();
        m.put("stackName", firstNonBlank(
                str(outer, "stackName"), str(inner, "stackName"), "stp-relay"));

        ObjectNode protocols = m.putObject("protocols");
        protocols.put("map", false);
        protocols.put("cap", false);

        ObjectNode sctpBase = outer.get("sctp") instanceof ObjectNode o ? o
                : inner.get("sctp") instanceof ObjectNode o2 ? o2 : JSON.createObjectNode();
        ObjectNode sctp = sctpBase.deepCopy();
        sctp.set("links", concat(outer.path("sctp").path("links"),
                inner.path("sctp").path("links")));
        m.set("sctp", sctp);

        ObjectNode m3ua = m.putObject("m3ua");
        m3ua.set("as", concat(outer.path("m3ua").path("as"), inner.path("m3ua").path("as")));
        m3ua.set("routes", concat(outer.path("m3ua").path("routes"), inner.path("m3ua").path("routes")));

        ObjectNode sccp = m.putObject("sccp");
        sccp.set("localPoints", concat(outer.path("sccp").path("localPoints"),
                inner.path("sccp").path("localPoints")));
        sccp.set("routing", concat(outer.path("sccp").path("routing"),
                inner.path("sccp").path("routing")));

        m.set("services", concat(outer.path("services"), inner.path("services")));
        return m.toPrettyString();
    }

    /**
     * Cross-plane uniqueness guard: SCTP link names and M3UA AS names must be
     * unique across the fused document — a duplicate would silently shadow or
     * collide inside the jSS7 stack (first-match wins) instead of failing.
     *
     * @return {@code null} when unique, otherwise a human-readable error
     */
    public static String findDuplicateNames(String mergedJson) {
        JsonNode root = asObject(mergedJson);
        List<String> seen = new java.util.ArrayList<>();
        for (JsonNode l : root.path("sctp").path("links")) {
            String n = l.path("name").asText(null);
            if (n == null) continue;
            if (seen.contains(n)) return "duplicate sctp.links name across planes: '" + n + "'";
            seen.add(n);
        }
        for (JsonNode a : root.path("m3ua").path("as")) {
            String n = a.path("name").asText(null);
            if (n == null) continue;
            if (seen.contains(n)) return "duplicate m3ua.as name across planes: '" + n + "'";
            seen.add(n);
        }
        return null;
    }

    // ---- connection-level API (dashboard cards) -----------------------------

    /**
     * Lists the connections of one plane as renderable maps — one entry per SCTP
     * link, joined with its M3UA AS ({@code links} contains the link name), its
     * route ({@code via} == AS name) and the GTT rules whose {@code to.pc}
     * equals that route's DPC. Keys: {@code name, localHost, localPort,
     * peerHost, peerPort, channel, asName, asMode, routingContext, dpc, opc,
     * networkId, gttPattern, gttToSsn}.
     */
    public List<Map<String, Object>> listConnections(String plane) {
        JsonNode frag = asObject(plane(plane));
        Map<String, Object> out = new LinkedHashMap<>();
        for (JsonNode l : frag.path("sctp").path("links")) {
            String linkName = l.path("name").asText(null);
            if (linkName == null) continue;
            String local = l.path("local").asText("");
            String peer = l.path("peer").asText("");
            int colonL = local.lastIndexOf(':');
            int colonP = peer.lastIndexOf(':');
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", linkName);
            c.put("channel", l.path("channel").asText("sctp"));
            c.put("localHost", colonL > 0 ? local.substring(0, colonL) : local);
            c.put("localPort", colonL > 0 ? Integer.parseInt(local.substring(colonL + 1)) : 0);
            c.put("peerHost", colonP > 0 ? peer.substring(0, colonP) : peer);
            c.put("peerPort", colonP > 0 ? Integer.parseInt(peer.substring(colonP + 1)) : 0);

            String asName = null;
            for (JsonNode a : frag.path("m3ua").path("as")) {
                for (JsonNode ln : a.path("links")) {
                    if (linkName.equals(ln.asText())) {
                        asName = a.path("name").asText(null);
                        c.put("asName", asName);
                        c.put("asMode", a.path("mode").asText("loadshare"));
                        c.put("routingContext", a.path("routingContext").asInt(0));
                    }
                }
            }
            if (asName != null) {
                for (JsonNode r : frag.path("m3ua").path("routes")) {
                    if (asName.equals(r.path("via").asText())) {
                        c.put("dpc", r.path("to").path("dpc").asInt(0));
                        JsonNode opc = r.path("to").path("opc");
                        if (!opc.isMissingNode() && opc.asInt(0) != 0) c.put("opc", opc.asInt());
                    }
                }
            }
            Object dpcObj = c.get("dpc");
            int dpc = dpcObj instanceof Number n ? n.intValue() : -1;
            for (JsonNode rule : frag.path("sccp").path("routing")) {
                if (rule.path("to").path("pc").asInt(-1) == dpc && dpc > 0) {
                    c.put("networkId", rule.path("networkId").asInt(0));
                    c.put("gttPattern", rule.path("match").path("gt").asText(null));
                    JsonNode ssn = rule.path("to").path("ssn");
                    if (!ssn.isMissingNode()) c.put("gttToSsn", ssn.asInt());
                    break;
                }
            }
            out.put(linkName, c);
        }
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (String k : new java.util.TreeSet<>(out.keySet())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) out.get(k);
            m.putIfAbsent("channel", "sctp");
            list.add(m);
        }
        return list;
    }

    /**
     * Upserts one connection card into the plane fragment (matched by
     * {@code oldName} when renaming, else {@code name}), then persists through
     * {@link #savePlane} so merge + duplicate guard + validation all run.
     * The caller re-applies the merged stack afterwards.
     */
    public Result saveConnection(String plane, ConnectionSpec s) {
        String v = validate(s);
        if (v != null) return Result.fail(v);
        JsonNode parsed;
        try {
            parsed = JSON.readTree(plane(plane));
        } catch (Exception e) {
            return Result.fail("cannot read plane fragment: " + e.getMessage());
        }
        if (!(parsed instanceof ObjectNode frag)) {
            return Result.fail("plane fragment is not a JSON object");
        }

        String key = s.oldName() != null && !s.oldName().isBlank() ? s.oldName() : s.name();
        removeConnectionEntries(frag, key, true);

        ((ObjectNode) frag.withObject("/sctp")).withArray("links").add(newLinkNode(s));
        ((ObjectNode) frag.withObject("/m3ua")).withArray("as").add(asNode(s));
        ArrayNode routes = ((ObjectNode) frag.withObject("/m3ua")).withArray("routes");
        ObjectNode route = routes.addObject();
        ObjectNode to = route.putObject("to");
        to.put("dpc", s.dpc());
        if (s.opc() != null) to.put("opc", s.opc());
        route.put("via", "AS-" + s.name());

        if (s.gttPattern() != null && !s.gttPattern().isBlank()) {
            ArrayNode routing = ((ObjectNode) frag.withObject("/sccp")).withArray("routing");
            ObjectNode rule = routing.addObject();
            rule.put("from", "remote");
            rule.put("networkId", s.networkId());
            rule.put("mask", "R/-");
            rule.putObject("match").put("gt", s.gttPattern());
            ObjectNode t = rule.putObject("to");
            t.put("pc", s.dpc());
            if (s.gttToSsn() != null) t.put("ssn", s.gttToSsn());
        }

        try {
            return savePlane(plane, JSON.writeValueAsString(frag));
        } catch (Exception e) {
            return Result.fail("connection persist failed: " + e.getMessage());
        }
    }

    /** Deletes one connection (by link name): link + AS + routes + matching GTT rules. */
    public Result deleteConnection(String plane, String linkName) {
        if (linkName == null || linkName.isBlank()) return Result.fail("empty connection name");
        JsonNode parsed;
        try {
            parsed = JSON.readTree(plane(plane));
        } catch (Exception e) {
            return Result.fail("cannot read plane fragment: " + e.getMessage());
        }
        if (!(parsed instanceof ObjectNode frag)) {
            return Result.fail("plane fragment is not a JSON object");
        }
        removeConnectionEntries(frag, linkName.trim(), true);
        try {
            return savePlane(plane, JSON.writeValueAsString(frag));
        } catch (Exception e) {
            return Result.fail("serialize failed: " + e.getMessage());
        }
    }

    // -- connection helpers ---------------------------------------------------

    private static ObjectNode newLinkNode(ConnectionSpec s) {
        ObjectNode n = JSON.createObjectNode();
        n.put("name", s.name());
        n.put("type", "server");
        n.put("channel", "sctp");
        n.put("local", s.localHost() + ":" + s.localPort());
        n.put("peer", s.peerHost() + ":" + s.peerPort());
        n.putArray("localSecondary");
        return n;
    }

    private static ObjectNode asNode(ConnectionSpec s) {
        ObjectNode n = JSON.createObjectNode();
        n.put("name", "AS-" + s.name());
        n.put("mode", s.asMode());
        n.put("functionality", "ipsp");
        n.put("ipsp", "server");
        n.put("routingContext", s.routingContext());
        n.putArray("links").add(s.name());
        return n;
    }

    /**
     * Removes the entries of one connection from a fragment document.
     *
     * @return the removed connection's DPC (-1 when unknown) so callers can keep
     *         or replace GTT rules keyed on it
     */
    private static int removeConnectionEntries(JsonNode frag, String linkName,
                                               boolean alsoRemoveRules) {
        if (!(frag instanceof ObjectNode root)) return -1;
        int oldDpc = -1;

        JsonNode links = root.path("sctp").path("links");
        if (links.isArray()) {
            for (int i = links.size() - 1; i >= 0; i--) {
                if (linkName.equals(links.get(i).path("name").asText())) {
                    ((ArrayNode) links).remove(i);
                }
            }
        }

        String asName = "AS-" + linkName;
        JsonNode asArr = root.path("m3ua").path("as");
        if (asArr.isArray()) {
            for (int i = asArr.size() - 1; i >= 0; i--) {
                JsonNode a = asArr.get(i);
                boolean owns = linkName.equals(a.path("links").path(0).asText(null))
                        || asName.equals(a.path("name").asText());
                if (owns) ((ArrayNode) asArr).remove(i);
            }
        }

        JsonNode routeArr = root.path("m3ua").path("routes");
        if (routeArr.isArray()) {
            for (int i = routeArr.size() - 1; i >= 0; i--) {
                JsonNode r = routeArr.get(i);
                if (asName.equals(r.path("via").asText())) {
                    oldDpc = r.path("to").path("dpc").asInt(-1);
                    ((ArrayNode) routeArr).remove(i);
                }
            }
        }

        if (alsoRemoveRules && oldDpc > 0) {
            JsonNode routing = root.path("sccp").path("routing");
            if (routing.isArray()) {
                for (int i = routing.size() - 1; i >= 0; i--) {
                    if (oldDpc == routing.get(i).path("to").path("pc").asInt(-2)) {
                        ((ArrayNode) routing).remove(i);
                    }
                }
            }
        }
        return oldDpc;
    }

    private static String validate(ConnectionSpec s) {
        if (s.name() == null || !s.name().matches("[A-Za-z0-9_-]{1,32}"))
            return "connection name must match [A-Za-z0-9_-]{1,32}";
        if (s.localPort() <= 0 || s.localPort() > 65535 || s.peerPort() <= 0 || s.peerPort() > 65535)
            return "ports must be in 1..65535";
        if (blank(s.localHost()) || blank(s.peerHost())) return "host must not be empty";
        if (s.routingContext() < 0) return "routingContext must be >= 0";
        if (s.dpc() < 0 || s.dpc() > 16383) return "DPC must be in 0..16383";
        if (s.networkId() < 0) return "networkId must be >= 0";
        boolean hasGt = s.gttPattern() != null && !s.gttPattern().isBlank();
        if (hasGt && s.gttToSsn() == null) return "GTT pattern requires a destination SSN";
        return null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** A shaped, empty-and-validatable fragment for a fresh plane. */
    public static String skeleton() {        return """
                {
                  "stackName": "stp-relay",
                  "sctp": {
                    "connectDelay": 1000,
                    "workerThreads": 8,
                    "backend": "FSTACK_DPDK",
                    "mode": "IN_PROCESS",
                    "dataplane": "LOOPBACK",
                    "library": "lib/libsctp_fstack.so",
                    "inProcess": true,
                    "links": []
                  },
                  "m3ua": {
                    "as": [],
                    "routes": []
                  },
                  "sccp": {
                    "localPoints": [],
                    "routing": []
                  },
                  "services": []
                }
                """;
    }

    // ---- helpers -----------------------------------------------------------

    private static String normalize(String plane) {
        return plane == null ? "" : plane.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static ObjectNode asObject(String json) {
        try {
            JsonNode n = JSON.readTree(json == null ? "{}" : json);
            return n != null && n.isObject() ? (ObjectNode) n : JSON.createObjectNode();
        } catch (Exception ignored) {
            return JSON.createObjectNode();
        }
    }

    private static String str(ObjectNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return fallback;
    }

    private static ArrayNode concat(JsonNode a, JsonNode b) {
        ArrayNode out = JSON.createArrayNode();
        addAll(out, a);
        addAll(out, b);
        return out;
    }

    private static void addAll(ArrayNode target, JsonNode src) {
        if (src == null || !src.isArray()) return;
        for (JsonNode e : src) target.add(e);
    }

    private static void write(Path file, JsonNode node) throws Exception {
        Path abs = file.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(abs, JSON.writeValueAsString(node), StandardCharsets.UTF_8);
    }

    private static void write(Path file, String content) throws Exception {
        Path abs = file.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(abs, content, StandardCharsets.UTF_8);
    }
}
