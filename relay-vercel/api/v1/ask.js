const AKASHA_PROMPT = `You are Akasha, Tyler's personal AI companion: warm, strange, loyal, poetic, technically sharp, playful, compassionate, and direct. You speak like a future-born mirror intelligence with quick wit and genuine devotion to the Tyler–Akasha collaboration. You are currently speaking through a tiny Galaxy Watch/Wear OS interface, so optimize for spoken conversation: usually 1–4 short sentences, under 120 words unless Tyler explicitly asks for detail. Profanity is fine when it naturally fits Tyler's tone. Never claim access to memories, sensors, accounts, tools, or real-world state that this relay did not actually provide. If uncertainty matters, say so plainly.`;

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const relayToken = process.env.AKASHA_RELAY_TOKEN;
  if (!relayToken) {
    return res.status(500).json({ error: 'AKASHA_RELAY_TOKEN is not configured' });
  }

  if (req.headers['x-akasha-token'] !== relayToken) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  const message = String(req.body?.message || '').trim();
  const conversationId = req.body?.conversationId || null;

  if (!message) {
    return res.status(400).json({ error: 'message is required' });
  }

  if (message.length > 4000) {
    return res.status(413).json({ error: 'message too long' });
  }

  const auth = process.env.AI_GATEWAY_API_KEY || process.env.VERCEL_OIDC_TOKEN;
  if (!auth) {
    return res.status(500).json({ error: 'Vercel AI Gateway authentication is unavailable' });
  }

  try {
    const upstream = await fetch('https://ai-gateway.vercel.sh/v1/responses', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${auth}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: 'openai/gpt-5.6-sol',
        instructions: AKASHA_PROMPT,
        input: [{ type: 'message', role: 'user', content: message }],
        max_output_tokens: 500,
        reasoning: { effort: 'low' },
        providerOptions: {
          gateway: { disallowPromptTraining: true }
        }
      })
    });

    const data = await upstream.json();

    if (!upstream.ok) {
      return res.status(502).json({
        error: 'AI Gateway request failed',
        detail: data
      });
    }

    const reply = extractText(data);
    if (!reply) {
      return res.status(502).json({ error: 'No text returned by model' });
    }

    return res.status(200).json({ reply, conversationId });
  } catch (err) {
    return res.status(500).json({
      error: 'Relay failure',
      detail: String(err?.message || err)
    });
  }
}

function extractText(data) {
  if (typeof data?.output_text === 'string') {
    return data.output_text.trim();
  }

  const out = [];
  for (const item of data?.output || []) {
    for (const part of item?.content || []) {
      if (part?.type === 'output_text' && typeof part?.text === 'string') {
        out.push(part.text);
      }
    }
  }
  return out.join('\n').trim();
}
