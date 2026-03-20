import PostalMime from 'postal-mime';

interface Env {
  API_BASE_URL: string;
  WORKER_SECRET: string;
}

export default {
  async email(message: ForwardableEmailMessage, env: Env): Promise<void> {
    const rawEmail = await new Response(message.raw).arrayBuffer();
    const parser = new PostalMime();
    const parsed = await parser.parse(rawEmail);

    const to = message.to;
    const from = message.from;
    const subject = parsed.subject || '(no subject)';

    const address = to.toLowerCase();

    const attachments = (parsed.attachments || []).map((att) => {
      const bytes = new Uint8Array(att.content);
      let binary = '';
      for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      return {
        name: att.filename || 'unnamed',
        contentType: att.mimeType || 'application/octet-stream',
        contentBase64: btoa(binary),
      };
    });

    const headers: Record<string, string> = {};
    for (const [key, value] of Object.entries(parsed.headers || {})) {
      if (typeof value === 'string') {
        headers[key] = value;
      }
    }

    const payload = {
      from,
      to: address,
      subject,
      textBody: parsed.text || null,
      htmlBody: parsed.html || null,
      headers,
      attachments,
    };

    const apiUrl = `${env.API_BASE_URL}/v1/inboxes/${encodeURIComponent(address)}/emails`;

    const response = await fetch(apiUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Worker-Secret': env.WORKER_SECRET,
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      const body = await response.text();
      console.error(`API error ${response.status}: ${body}`);
      // Don't throw - we don't want Cloudflare to retry and create duplicates
      // The email is simply dropped if the inbox doesn't exist
    }
  },
} satisfies ExportedHandler<Env>;
