const https = require('https');

// ---------------------------------------------------------------------------
// Allowed upstream hosts. This function is a *fixed* reverse proxy, never an
// open one: the caller may only reach a host on this list. Anything else gets
// a 403 before we make an outbound request (SSRF guard).
//
//   - beta.cors-fox.cc  -> the Cors.Connect API backend (/api/app/*, /api/auth/*)
//   - panel.cors-fox.cc -> the Remnawave panel (subscription feed: /sub/<token>,
//                          /api/sub/*). Routed here so the app can refresh its
//                          Xray subscription even where the panel is blocked.
// ---------------------------------------------------------------------------
const DEFAULT_HOST = 'beta.cors-fox.cc';
const ALLOWED_HOSTS = new Set([
    'beta.cors-fox.cc',
    'panel.cors-fox.cc',
]);

const PATH_PARAM = '__path';
const HOST_PARAM = '__host';

// Must match CorsClient.PROXY_BEARER_HEADER on the Android side (case-insensitive
// on the wire, but keep them in sync for clarity). Yandex Cloud Functions
// intercepts a literal "Authorization" header for its own invocation auth and
// rejects the request with its own 403 ("Forbidden: Not authorized") before
// this handler ever runs -- that's what was silently breaking every heartbeat
// call (the only endpoint that sends a bearer token). The client instead sends
// the app session token under this header, and we translate it back into a
// real "Authorization: Bearer ..." header on the outbound request to the real
// backend, which still expects a normal bearer token.
const SESSION_TOKEN_HEADER = 'x-cors-session-token';

// Upstream response headers that are safe to forward to the caller and that the
// app actually needs. Everything else is dropped -- Yandex forbids returning
// platform-managed / hop-by-hop headers (Via, Server, Date, Connection, ...)
// and copying e.g. "via: 1.1 Caddy" fails the whole response with "Invalid
// response header specified". The Remnawave subscription feed carries its quota
// and profile metadata in these headers, so they must survive the proxy.
const PASSTHROUGH_RESPONSE_HEADERS = [
    'content-type',
    'content-disposition',
    'subscription-userinfo',
    'profile-title',
    'profile-update-interval',
    'profile-web-page-url',
    'support-url',
    'announce',
];

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-App-Token, X-Cors-Session-Token, X-Hwid, X-Device-Os, X-Ver-Os, X-Device-Model',
    'Access-Control-Expose-Headers': PASSTHROUGH_RESPONSE_HEADERS.join(', '),
    'Access-Control-Max-Age': '86400',
};

module.exports.handler = async function (event) {
    if (event.httpMethod === 'OPTIONS') {
        return { statusCode: 204, headers: corsHeaders, body: '' };
    }

    try {
        const qs = event.queryStringParameters || {};

        // Resolve (and authorize) the upstream host. Absent -> default backend.
        const targetHost = (qs[HOST_PARAM] || DEFAULT_HOST).toLowerCase();
        if (!ALLOWED_HOSTS.has(targetHost)) {
            return {
                statusCode: 403,
                headers: { ...corsHeaders, 'content-type': 'application/json' },
                body: JSON.stringify({ detail: `Proxy: host not allowed: ${targetHost}` }),
            };
        }
        const targetBackendUrl = `https://${targetHost}`;

        // Backend path arrives via __path query param (Yandex
        // functions.yandexcloud.net has no path routing). It may itself contain
        // a query string (e.g. /sub/<token>?format=v2ray).
        let rawPath = qs[PATH_PARAM] || event.path || '/';
        if (!rawPath.startsWith('/')) rawPath = '/' + rawPath;

        // Rebuild upstream query string from any *extra* params, dropping our
        // own control params. Params already folded into __path are preserved
        // because rawPath is passed to new URL() verbatim.
        const upstreamQuery = Object.keys(qs)
            .filter(k => k !== PATH_PARAM && k !== HOST_PARAM)
            .map(k => `${encodeURIComponent(k)}=${encodeURIComponent(qs[k])}`)
            .join('&');
        const joiner = rawPath.includes('?') ? '&' : '?';
        const targetUrl = new URL(
            rawPath + (upstreamQuery ? joiner + upstreamQuery : ''),
            targetBackendUrl,
        );

        // Forward request headers, but force Host to the resolved backend.
        const requestHeaders = { ...(event.headers || {}) };
        delete requestHeaders.host;
        delete requestHeaders.Host;
        delete requestHeaders['content-length'];
        delete requestHeaders['Content-Length'];
        requestHeaders['host'] = targetHost;

        // Translate the smuggled session-token header into a real bearer
        // Authorization header for the upstream backend. Any literal
        // Authorization header from the caller never reaches us (Yandex's
        // platform strips/rejects it first), so this is the only path a
        // bearer-authenticated request can take through the proxy.
        //
        // Header keys arrive with whatever casing Yandex/the client used, so
        // scan case-insensitively rather than guessing one exact casing.
        const tokenKey = Object.keys(requestHeaders).find(
            k => k.toLowerCase() === SESSION_TOKEN_HEADER
        );
        const sessionToken = tokenKey ? requestHeaders[tokenKey] : undefined;
        if (sessionToken) {
            requestHeaders['authorization'] = `Bearer ${sessionToken}`;
        }
        if (tokenKey) delete requestHeaders[tokenKey];

        let bodyPayload = event.body;
        if (bodyPayload && event.isBase64Encoded) {
            bodyPayload = Buffer.from(bodyPayload, 'base64');
        }
        if (bodyPayload) {
            requestHeaders['content-length'] = Buffer.byteLength(bodyPayload);
        }

        console.log(`[PROXY] ${event.httpMethod} ${targetUrl.toString()} auth=${sessionToken ? 'bearer' : 'none'}`);

        const responseData = await makeRequest(targetUrl, event.httpMethod, requestHeaders, bodyPayload);

        // Return only CORS + the explicitly whitelisted upstream headers.
        const responseHeaders = { ...corsHeaders };
        for (const name of PASSTHROUGH_RESPONSE_HEADERS) {
            const value = responseData.headers[name];
            if (value !== undefined && value !== null) {
                responseHeaders[name] = Array.isArray(value) ? value.join(', ') : value;
            }
        }
        if (!responseHeaders['content-type']) {
            responseHeaders['content-type'] = 'application/json';
        }

        return {
            statusCode: responseData.statusCode,
            headers: responseHeaders,
            body: responseData.body,
            isBase64Encoded: responseData.isBase64Encoded,
        };
    } catch (error) {
        console.error('[PROXY ERROR]', error);
        return {
            statusCode: 502,
            headers: { ...corsHeaders, 'content-type': 'application/json' },
            body: JSON.stringify({ detail: 'Proxy Error: ' + (error && error.message ? error.message : String(error)) }),
        };
    }
};

function makeRequest(url, method, headers, payload) {
    return new Promise((resolve, reject) => {
        const req = https.request(url, { method, headers }, (res) => {
            const chunks = [];
            res.on('data', (chunk) => chunks.push(chunk));
            res.on('end', () => {
                const buffer = Buffer.concat(chunks);
                const contentType = res.headers['content-type'] || '';
                const isText = contentType.includes('json') ||
                               contentType.includes('text') ||
                               contentType.includes('html');
                resolve({
                    statusCode: res.statusCode,
                    headers: res.headers,
                    body: isText ? buffer.toString('utf-8') : buffer.toString('base64'),
                    isBase64Encoded: !isText,
                });
            });
        });
        req.on('error', reject);
        if (payload) req.write(payload);
        req.end();
    });
}
