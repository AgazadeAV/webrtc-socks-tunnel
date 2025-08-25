package main

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"
)

type iceServer struct {
	Urls       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
}

type iceResp struct {
	IceServers []iceServer `json:"iceServers"`
	TTL        int         `json:"ttl"`
}

func sign(secret, msg string) string {
	h := hmac.New(sha1.New, []byte(secret))
	h.Write([]byte(msg))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

func main() {
	secret := os.Getenv("TURN_SECRET")
	host := os.Getenv("TURN_HOST")
	ttlStr := os.Getenv("TURN_TTL")
	ttl, _ := strconv.Atoi(ttlStr)
	if ttl == 0 {
		ttl = 3600
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/ice", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")

		u := r.URL.Query().Get("u")
		if u == "" {
			u = "anon"
		}

		exp := time.Now().Add(time.Duration(ttl) * time.Second).Unix()
		username := strconv.FormatInt(exp, 10) + ":" + u
		cred := sign(secret, username)

		resp := iceResp{
			IceServers: []iceServer{{
				Urls: []string{
					"turn:" + host + ":3478?transport=udp",
					"turn:" + host + ":3478?transport=tcp",
				},
				Username:   username,
				Credential: cred,
			}},
			TTL: ttl,
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	})

	log.Println("ICE API running on :8080")
	log.Fatal(http.ListenAndServe(":8080", mux))
}
