import PostalMime from 'postal-mime';

interface Env {
  API_BASE_URL: string;
  WORKER_SECRET: string;
}

const FETCH_TIMEOUT_MS = 25_000;
const RETRYABLE_STATUSES = new Set([429, 502, 503]);
const RETRY_DELAYS_MS = [1_000, 4_000];

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
      const bytes =
        typeof att.content === 'string'
          ? new TextEncoder().encode(att.content)
          : new Uint8Array(att.content);
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

    // The API doesn't deduplicate deliveries, so only retry responses that mean the
    // request never reached it: rate limiting, or the proxy's answer while the API
    // restarts during a deploy. A timeout or a 500 may have stored the email already.
    let response: Response;
    for (let attempt = 0; ; attempt++) {
      try {
        response = await fetch(apiUrl, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Worker-Secret': env.WORKER_SECRET,
          },
          body: JSON.stringify(payload),
          signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
        });
      } catch (err) {
        // Timeout or network failure: the API may or may not have stored the email.
        console.error(`API request failed for ${address}: ${err}`);
        message.setReject('Temporary failure, please retry');
        return;
      }
      if (!RETRYABLE_STATUSES.has(response.status) || attempt >= RETRY_DELAYS_MS.length) break;
      console.warn(`API returned ${response.status} for ${address}; retrying`);
      await response.body?.cancel();
      await new Promise((resolve) => setTimeout(resolve, RETRY_DELAYS_MS[attempt]));
    }

    if (response.ok) return;

    const body = await response.text().catch(() => '');
    console.error(`API error ${response.status} for ${address}: ${body}`);

    // setReject() always produces a permanent (5xx) SMTP rejection; the Workers
    // API has no temporary-failure variant. Rejecting still beats the previous
    // behaviour of accepting and silently dropping the message.
    if (response.status >= 500 || response.status === 429) {
      message.setReject('Temporary failure, please retry');
    } else {
      message.setReject(`Message rejected: ${response.status}`);
    }
  },
} satisfies ExportedHandler<Env>;
