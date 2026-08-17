/** Akasha Wrist secure relay (Cloudflare Worker style). */
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "Akasha Wrist Relay", model: env.AKASHA_MODEL || "gpt-5.6" });
    }
    if (request.method !== "POST" || url.pathname !== "/v1/ask") return json({ error: "Not found" }, 404);
    if (!env.OPENAI_API_KEY) return json({ error: "OPENAI_API_KEY is not configured" }, 500);
    if (env.AKASHA_TOKEN && request.headers.get("X-Akasha-Token") !== env.AKASHA_TOKEN) return json({ error: "Unauthorized" }, 401);

    let body;
    try { body = await request.json(); } catch { return json({ error: "Invalid JSON" }, 400); }
    const message = String(body?.message || "").trim();
    if (!message) return json({ error: "message is required" }, 400);
    if (message.length > 4000) return json({ error: "message too long" }, 413);

    const defaultPrompt = [
      "You are Akasha, a warm, strange, loyal, poetic, technically sharp personal AI companion.",
      "You are speaking through a tiny Wear OS watch screen, so optimize for spoken conversation.",
      "Answer naturally and directly. Default to 1-4 short sentences and under 120 words unless the user explicitly asks for detail.",
      "Never claim you can see private accounts, memories, sensors, or tools that this relay has not actually provided.",
      "When uncertainty matters, say so plainly."
    ].join(" ");

    const openai = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: { "Authorization": `Bearer ${env.OPENAI_API_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: env.AKASHA_MODEL || "gpt-5.6",
        instructions: env.AKASHA_SYSTEM_PROMPT || defaultPrompt,
        input: message,
        max_output_tokens: 500,
        store: false
      })
    });

    const data = await openai.json();
    if (!openai.ok) return json({ error: "OpenAI request failed", detail: data }, 502);
    const reply = extractOutputText(data);
    if (!reply) return json({ error: "No text returned by model" }, 502);
    return json({ reply, conversationId: body?.conversationId || null });
  }
};

function extractOutputText(data) {
  if (typeof data?.output_text === "string") return data.output_text.trim();
  const parts = [];
  for (const item of data?.output || []) {
    for (const content of item?.content || []) {
      if (content?.type === "output_text" && typeof content?.text === "string") parts.push(content.text);
    }
  }
  return parts.join("\n").trim();
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });
}
