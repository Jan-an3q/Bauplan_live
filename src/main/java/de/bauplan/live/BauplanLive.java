package de.bauplan.live;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Kleiner lokaler Server (nur 127.0.0.1:25599), der Spieler- und Blockdaten als JSON liefert. */
public class BauplanLive implements ClientModInitializer {
	static final int PORT = 25599;

	@Override
	public void onInitializeClient() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
			server.createContext("/state", this::handle);
			server.createContext("/skin", this::skin);
			server.setExecutor(Executors.newFixedThreadPool(2, r -> {
				Thread t = new Thread(r, "bauplan-live-http");
				t.setDaemon(true);
				return t;
			}));
			server.start();
			System.out.println("[bauplan_live] laeuft auf http://127.0.0.1:" + PORT);
		} catch (Exception e) {
			System.err.println("[bauplan_live] Server konnte nicht starten: " + e);
		}
	}

	private void handle(HttpExchange ex) throws IOException {
		ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		String body;
		int code = 200;
		try {
			Map<String, Integer> q = parse(ex.getRequestURI().getRawQuery());
			Minecraft mc = Minecraft.getInstance();
			CompletableFuture<String> f = new CompletableFuture<>();
			mc.execute(() -> {
				try { f.complete(snapshot(mc, q)); } catch (Throwable t) { f.completeExceptionally(t); }
			});
			body = f.get(3, TimeUnit.SECONDS);
		} catch (Exception e) {
			code = 500;
			body = "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
		}
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(code, b.length);
		try (var os = ex.getResponseBody()) { os.write(b); }
	}

	private static volatile byte[] skinPng;
	private static volatile boolean slim, fetching;
	private static volatile long nextTry;

	private void skin(HttpExchange ex) throws IOException {
		ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		byte[] b = skinPng;
		if (b == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
		ex.getResponseHeaders().add("Content-Type", "image/png");
		ex.sendResponseHeaders(200, b.length);
		try (var os = ex.getResponseBody()) { os.write(b); }
	}

	/** Holt den Skin einmalig bei Mojang (nur wenn ein normales Konto genutzt wird). */
	private static void requestSkin(String uuid) {
		if (skinPng != null || fetching || System.currentTimeMillis() < nextTry) return;
		fetching = true;
		Thread t = new Thread(() -> {
			try {
				HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
				String prof = c.send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
					.timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
				Matcher m = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"").matcher(prof);
				if (!m.find()) throw new IOException("kein Profil");
				String tex = new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8);
				Matcher u = Pattern.compile("\"SKIN\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(tex);
				if (!u.find()) throw new IOException("kein Skin");
				slim = tex.contains("\"slim\"");
				skinPng = c.send(HttpRequest.newBuilder(URI.create(u.group(1))).timeout(Duration.ofSeconds(8)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray()).body();
			} catch (Exception e) {
				nextTry = System.currentTimeMillis() + 60000;
			} finally {
				fetching = false;
			}
		}, "bauplan-skin");
		t.setDaemon(true);
		t.start();
	}

	private static Map<String, Integer> parse(String raw) {
		Map<String, Integer> m = new HashMap<>();
		if (raw == null) return m;
		for (String part : raw.split("&")) {
			int i = part.indexOf('=');
			if (i < 1) continue;
			try { m.put(part.substring(0, i), Integer.parseInt(part.substring(i + 1))); } catch (NumberFormatException ignored) { }
		}
		return m;
	}

	private static String snapshot(Minecraft mc, Map<String, Integer> q) {
		var p = mc.player;
		var lv = mc.level;
		if (p == null || lv == null) return "{\"inGame\":false}";
		StringBuilder sb = new StringBuilder(4096);
		sb.append("{\"inGame\":true,\"p\":{\"x\":").append(p.getX())
			.append(",\"y\":").append(p.getY())
			.append(",\"z\":").append(p.getZ())
			.append(",\"yaw\":").append(p.getYRot())
			.append(",\"pitch\":").append(p.getXRot())
			.append(",\"sneak\":").append(p.isCrouching())
			.append(",\"hand\":\"").append(BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem())).append("\"")
			.append(",\"off\":\"").append(BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem())).append("\"}");

		String uuid = p.getUUID().toString().replace("-", "");
		requestSkin(uuid);
		sb.append(",\"uuid\":\"").append(uuid).append("\",\"skin\":").append(skinPng != null).append(",\"slim\":").append(slim);

		if (q.containsKey("x0") && q.containsKey("y0") && q.containsKey("z0")
			&& q.containsKey("x1") && q.containsKey("y1") && q.containsKey("z1")) {
			int r = Math.min(64, q.getOrDefault("r", 12));
			int px = (int) Math.floor(p.getX()), py = (int) Math.floor(p.getY()), pz = (int) Math.floor(p.getZ());
			int minX, maxX, minY, maxY, minZ, maxZ;
			while (true) {
				minX = Math.max(q.get("x0"), px - r); maxX = Math.min(q.get("x1"), px + r);
				minY = Math.max(q.get("y0"), py - r); maxY = Math.min(q.get("y1"), py + r);
				minZ = Math.max(q.get("z0"), pz - r); maxZ = Math.min(q.get("z1"), pz + r);
				long vol = (long) Math.max(0, maxX - minX + 1) * Math.max(0, maxY - minY + 1) * Math.max(0, maxZ - minZ + 1);
				if (vol <= 600000L || r <= 4) break;
				r -= 4;
			}
			List<String> pal = new ArrayList<>();
			Map<String, Integer> idx = new HashMap<>();
			StringBuilder bl = new StringBuilder();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int y = minY; y <= maxY; y++)
				for (int z = minZ; z <= maxZ; z++)
					for (int x = minX; x <= maxX; x++) {
						pos.set(x, y, z);
						var st = lv.getBlockState(pos);
						if (st.isAir()) continue;
						String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
						Integer k = idx.get(id);
						if (k == null) { k = pal.size(); idx.put(id, k); pal.add(id); }
						if (bl.length() > 0) bl.append(',');
						bl.append(x).append(',').append(y).append(',').append(z).append(',').append(k);
					}
			sb.append(",\"reg\":[").append(minX).append(',').append(minY).append(',').append(minZ).append(',')
				.append(maxX).append(',').append(maxY).append(',').append(maxZ).append("],\"pal\":[");
			for (int i = 0; i < pal.size(); i++) {
				if (i > 0) sb.append(',');
				sb.append('"').append(pal.get(i)).append('"');
			}
			sb.append("],\"b\":[").append(bl).append(']');
		}
		return sb.append('}').toString();
	}
}
