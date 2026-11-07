// 提供设备固定入口和单次配对注册服务。
// Provides stable device URLs and single-use pairing registration.
package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/crypto/acme/autocert"
	_ "modernc.org/sqlite"
)

var devicePattern = regexp.MustCompile(`^[a-zA-Z0-9_-]{16,80}$`)
var tunnelPattern = regexp.MustCompile(`^https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.trycloudflare\.com$`)
var codePattern = regexp.MustCompile(`^[0-9]{6}$`)
var opticalSecretPattern = regexp.MustCompile(`^[a-f0-9]{32}$`)

type service struct {
	db     *sql.DB
	base   string
	mu     sync.Mutex
	limits map[string]rate
	probe  func(context.Context, string) bool
}

// 页面与样式随二进制部署，避免依赖外部静态资源。
// Pages and styles ship with the binary without external assets.
//
//go:embed web/*
var pages embed.FS

type rate struct {
	count int
	until time.Time
}
type registration struct {
	DeviceID  string `json:"device_id"`
	Name      string `json:"name"`
	TunnelURL string `json:"tunnel_url"`
}
type pairing struct {
	DeviceID    string `json:"device_id"`
	AccessToken string `json:"access_token"`
}
type claim struct {
	Code       string `json:"code"`
	ScanSecret string `json:"scan_secret"`
}

func digest(value string) string { h := sha256.Sum256([]byte(value)); return hex.EncodeToString(h[:]) }
func randomToken() string {
	var value [32]byte
	if _, err := rand.Read(value[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(value[:])
}
func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
func respond(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func failure(w http.ResponseWriter, status int, message string) {
	respond(w, status, map[string]string{"error": message})
}

// 错误码供客户端识别业务状态，避免把配对失效误报为接口不存在。
// Error codes distinguish pairing states from missing API routes.
func pairingFailure(w http.ResponseWriter, status int, code, message string) {
	respond(w, status, map[string]string{"code": code, "error": message})
}
func decode(w http.ResponseWriter, r *http.Request, value any) bool {
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
	d.DisallowUnknownFields()
	if err := d.Decode(value); err != nil {
		failure(w, 400, "请求格式不正确")
		return false
	}
	var extra any
	if err := d.Decode(&extra); !errors.Is(err, io.EOF) {
		failure(w, 400, "请求格式不正确")
		return false
	}
	return true
}

func openService(path, base string) (*service, error) {
	u, err := url.Parse(base)
	if err != nil || u.Host == "" || u.RawQuery != "" || u.Fragment != "" || u.User != nil || (u.Scheme != "https" && !(u.Scheme == "http" && (u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost"))) {
		return nil, fmt.Errorf("PUBLIC_URL 必须为 HTTPS 地址")
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, key_hash TEXT NOT NULL, name TEXT NOT NULL, tunnel_url TEXT NOT NULL, seen INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS pairings(id TEXT PRIMARY KEY, code_hash TEXT UNIQUE NOT NULL, scan_hash TEXT NOT NULL, device_id TEXT NOT NULL UNIQUE, token TEXT NOT NULL, expires INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0);`)
	if err != nil {
		db.Close()
		return nil, err
	}
	client := &http.Client{Timeout: 4 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	return &service{db: db, base: strings.TrimRight(base, "/"), limits: map[string]rate{}, probe: tunnelProbe(client)}, nil
}

// 访问前确认 AP 仍在响应；不跟随目标跳转，也不传递注册或配对凭据。
// Verifies AP before redirecting, without forwarding credentials or following redirects.
func tunnelProbe(client *http.Client) func(context.Context, string) bool {
	return func(ctx context.Context, target string) bool {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, target+"/healthz", nil)
		if err != nil {
			return false
		}
		response, err := client.Do(request)
		if err != nil {
			return false
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			return false
		}
		var health struct {
			Status          string `json:"status"`
			ProtocolVersion int    `json:"protocolVersion"`
		}
		return json.NewDecoder(io.LimitReader(response.Body, 1024)).Decode(&health) == nil && health.Status == "ok" && health.ProtocolVersion == 1
	}
}

func (s *service) allowed(key string, maximum int) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	if len(s.limits) > 10000 {
		for k, v := range s.limits {
			if now.After(v.until) {
				delete(s.limits, k)
			}
		}
		if len(s.limits) > 10000 {
			return false
		}
	}
	v := s.limits[key]
	if now.After(v.until) {
		v = rate{until: now.Add(time.Minute)}
	}
	v.count++
	s.limits[key] = v
	return v.count <= maximum
}

func bearer(r *http.Request) string {
	return strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
}

// 仅信任本机反代写入的地址，公网请求不能伪造限流来源。
// Trusts proxy addresses only from loopback, preventing public rate-limit spoofing.
func clientIP(r *http.Request) string {
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	if parsed := net.ParseIP(ip); parsed != nil && parsed.IsLoopback() {
		if forwarded := net.ParseIP(r.Header.Get("X-Real-IP")); forwarded != nil {
			return forwarded.String()
		}
	}
	return ip
}
func (s *service) authorized(r *http.Request, id string) bool {
	key := bearer(r)
	if len(key) < 43 || len(key) > 128 {
		return false
	}
	var hash string
	if s.db.QueryRow("SELECT key_hash FROM devices WHERE id=?", id).Scan(&hash) != nil {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(hash), []byte(digest(key))) == 1
}

func (s *service) register(w http.ResponseWriter, r *http.Request) {
	var p registration
	if !decode(w, r, &p) {
		return
	}
	key := bearer(r)
	if !devicePattern.MatchString(p.DeviceID) || !tunnelPattern.MatchString(p.TunnelURL) || len(key) < 43 || len(key) > 128 || len(p.Name) > 120 {
		failure(w, 400, "设备或隧道地址不正确")
		return
	}
	result, err := s.db.Exec(`INSERT INTO devices VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,tunnel_url=excluded.tunnel_url,seen=excluded.seen WHERE devices.key_hash=excluded.key_hash`, p.DeviceID, digest(key), p.Name, p.TunnelURL, time.Now().Unix())
	if err != nil {
		failure(w, 500, "注册暂不可用")
		return
	}
	count, _ := result.RowsAffected()
	if count == 0 {
		failure(w, 403, "设备凭据不正确")
		return
	}
	respond(w, 200, map[string]any{"url": s.base + "/d/" + p.DeviceID + "/", "lease_seconds": 180})
}

func (s *service) offline(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !s.authorized(r, id) {
		failure(w, 403, "设备凭据不正确")
		return
	}
	_, err := s.db.Exec("UPDATE devices SET seen=0 WHERE id=?", id)
	if err != nil {
		failure(w, 500, "状态更新失败")
		return
	}
	respond(w, 200, map[string]bool{"ok": true})
}

func (s *service) redirect(w http.ResponseWriter, r *http.Request) {
	var target string
	var seen int64
	if s.db.QueryRow("SELECT tunnel_url,seen FROM devices WHERE id=?", r.PathValue("id")).Scan(&target, &seen) != nil || time.Now().Unix()-seen > 180 {
		http.Redirect(w, r, "/error", http.StatusFound)
		return
	}
	if !tunnelPattern.MatchString(target) || !s.probe(r.Context(), target) {
		http.Redirect(w, r, "/error", http.StatusFound)
		return
	}
	u, _ := url.Parse(target)
	u.Path = "/" + r.PathValue("path")
	u.RawQuery = r.URL.RawQuery
	http.Redirect(w, r, u.String(), http.StatusMovedPermanently)
}

func (s *service) createPairing(w http.ResponseWriter, r *http.Request) {
	var p pairing
	if !decode(w, r, &p) {
		return
	}
	if !s.authorized(r, p.DeviceID) {
		failure(w, 403, "设备凭据不正确")
		return
	}
	if len(p.AccessToken) < 43 || len(p.AccessToken) > 128 {
		failure(w, 400, "配对凭据不正确")
		return
	}
	id, secret := randomToken(), randomToken()[:32]
	expires := time.Now().Add(5 * time.Minute).Unix()
	for attempt := 0; attempt < 10; attempt++ {
		n, err := rand.Int(rand.Reader, big.NewInt(1000000))
		if err != nil {
			failure(w, 500, "配对暂不可用")
			return
		}
		code := fmt.Sprintf("%06d", n.Int64())
		tx, err := s.db.Begin()
		if err != nil {
			failure(w, 500, "配对暂不可用")
			return
		}
		_, err = tx.Exec("DELETE FROM pairings WHERE device_id=? OR expires<?", p.DeviceID, time.Now().Unix())
		if err == nil {
			_, err = tx.Exec("INSERT INTO pairings(id,code_hash,scan_hash,device_id,token,expires) VALUES(?,?,?,?,?,?)", id, digest(code), digest(secret), p.DeviceID, p.AccessToken, expires)
		}
		if err != nil {
			tx.Rollback()
			continue
		}
		if tx.Commit() != nil {
			failure(w, 500, "配对暂不可用")
			return
		}
		respond(w, 200, map[string]any{"id": id, "code": code, "expires_at": expires, "particle_secret": secret})
		return
	}
	failure(w, 503, "配对繁忙，请稍后重试")
}

func (s *service) claim(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	if !s.allowed("claim:"+ip, 5) || !s.allowed("claim:global", 120) {
		pairingFailure(w, 429, "PAIRING_RATE_LIMITED", "尝试过于频繁，请稍后重试")
		return
	}
	var p claim
	if !decode(w, r, &p) {
		return
	}
	if !codePattern.MatchString(p.Code) && !(p.Code == "" && opticalSecretPattern.MatchString(p.ScanSecret)) {
		pairingFailure(w, 400, "INVALID_PAIRING_CODE", "请输入六位配对码")
		return
	}
	tx, err := s.db.Begin()
	if err != nil {
		failure(w, 500, "配对暂不可用")
		return
	}
	defer tx.Rollback()
	var id, device, token, scanHash, name, tunnel string
	var expires, seen int64
	var claimed int
	lookup, value := "p.code_hash", digest(p.Code)
	if p.Code == "" {
		lookup, value = "p.scan_hash", digest(p.ScanSecret)
	}
	err = tx.QueryRow(`SELECT p.id,p.device_id,p.token,p.scan_hash,p.expires,p.claimed,d.name,d.tunnel_url,d.seen FROM pairings p JOIN devices d ON d.id=p.device_id WHERE `+lookup+`=?`, value).Scan(&id, &device, &token, &scanHash, &expires, &claimed, &name, &tunnel, &seen)
	if err != nil || claimed != 0 || expires <= time.Now().Unix() || (p.ScanSecret != "" && subtle.ConstantTimeCompare([]byte(scanHash), []byte(digest(p.ScanSecret))) != 1) {
		pairingFailure(w, 410, "PAIRING_UNAVAILABLE", "配对码无效、已领取或已过期，请在 AP 重新生成")
		return
	}
	if time.Now().Unix()-seen > 180 {
		pairingFailure(w, 503, "DEVICE_OFFLINE", "AP 暂时离线，请确认 AP 的远程访问已就绪后重试")
		return
	}
	if _, err = tx.Exec("UPDATE pairings SET claimed=1,token='' WHERE id=? AND claimed=0", id); err != nil {
		failure(w, 500, "配对暂不可用")
		return
	}
	if tx.Commit() != nil {
		failure(w, 500, "配对暂不可用")
		return
	}
	respond(w, 200, map[string]string{"device_id": device, "name": name, "url": s.base + "/d/" + device + "/", "tunnel_url": tunnel, "access_token": token})
}

func (s *service) pairingStatus(w http.ResponseWriter, r *http.Request) {
	var device string
	var expires int64
	var claimed int
	if s.db.QueryRow("SELECT device_id,expires,claimed FROM pairings WHERE id=?", r.PathValue("id")).Scan(&device, &expires, &claimed) != nil {
		pairingFailure(w, 410, "PAIRING_EXPIRED", "配对已失效，请重新生成")
		return
	}
	if !s.authorized(r, device) {
		failure(w, 403, "设备凭据不正确")
		return
	}
	state := "pending"
	if claimed != 0 {
		state = "claimed"
	} else if expires <= time.Now().Unix() {
		state = "expired"
	}
	respond(w, 200, map[string]string{"state": state})
}

func (s *service) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/healthz", func(w http.ResponseWriter, r *http.Request) { respond(w, 200, map[string]string{"status": "ok"}) })
	mux.HandleFunc("POST /api/v1/devices/register", s.register)
	mux.HandleFunc("DELETE /api/v1/devices/{id}", s.offline)
	mux.HandleFunc("POST /api/v1/pairings", s.createPairing)
	mux.HandleFunc("POST /api/v1/pairings/claim", s.claim)
	mux.HandleFunc("GET /api/v1/pairings/{id}", s.pairingStatus)
	mux.HandleFunc("GET /d/{id}/{path...}", s.redirect)
	mux.HandleFunc("GET /d", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/error", http.StatusFound) })
	static, _ := fs.Sub(pages, "web")
	mux.Handle("GET /assets/", http.StripPrefix("/assets/", http.FileServer(http.FS(static))))
	for path, file := range map[string]string{"/{$}": "index.html", "/error": "error.html"} {
		content, _ := pages.ReadFile("web/" + file)
		mux.HandleFunc("GET "+path, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(content)
		})
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		ip := clientIP(r)
		if !s.allowed("all:"+ip, 120) {
			failure(w, 429, "请求过于频繁")
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func main() {
	s, err := openService(env("DATABASE_PATH", "remote-access.db"), env("PUBLIC_URL", "http://127.0.0.1:18882"))
	if err != nil {
		log.Fatal(err)
	}
	defer s.db.Close()
	srv := &http.Server{Addr: env("LISTEN_ADDR", "127.0.0.1:18882"), Handler: s.handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	}()
	log.Printf("远程注册服务监听 %s", srv.Addr)
	if hostname := os.Getenv("ACME_HOST"); hostname != "" {
		manager := &autocert.Manager{Prompt: autocert.AcceptTOS, Cache: autocert.DirCache(env("ACME_CACHE", "certificates")), HostPolicy: autocert.HostWhitelist(hostname)}
		srv.TLSConfig = manager.TLSConfig()
		challenge := &http.Server{Addr: env("ACME_LISTEN", "127.0.0.1:18883"), Handler: manager.HTTPHandler(nil), ReadHeaderTimeout: 5 * time.Second}
		go func() { <-ctx.Done(); _ = challenge.Close() }()
		go func() {
			if err := challenge.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				log.Printf("ACME 验证监听失败: %v", err)
			}
		}()
		err = srv.ListenAndServeTLS("", "")
	} else if cert, key := os.Getenv("TLS_CERT"), os.Getenv("TLS_KEY"); cert != "" && key != "" {
		err = srv.ListenAndServeTLS(cert, key)
	} else {
		err = srv.ListenAndServe()
	}
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
