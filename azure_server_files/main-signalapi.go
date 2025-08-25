package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
)

const (
	accountName   = "webrtcsignalstorage"
	containerName = "signals"
	listTimeout   = 20 * time.Second
	ioTimeout     = 20 * time.Second
	touchTimeout  = 10 * time.Second
)

type App struct {
	client *azblob.Client
}

func newApp() (*App, error) {
	cred, err := azidentity.NewDefaultAzureCredential(nil)
	if err != nil {
		return nil, fmt.Errorf("NewDefaultAzureCredential: %w", err)
	}
	url := fmt.Sprintf("https://%s.blob.core.windows.net/", accountName)
	client, err := azblob.NewClient(url, cred, nil)
	if err != nil {
		return nil, fmt.Errorf("azblob.NewClient: %w", err)
	}
	return &App{client: client}, nil
}

func badRequest(w http.ResponseWriter, msg string) {
	http.Error(w, msg, http.StatusBadRequest)
}

func methodNotAllowed(w http.ResponseWriter, allowed string) {
	w.Header().Set("Allow", allowed)
	http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
}

func validateKey(key string) error {
	if key == "" {
		return errors.New("empty key")
	}
	if strings.HasPrefix(key, "/") || strings.HasPrefix(key, "\\") {
		return errors.New("key must not start with a slash")
	}
	if strings.Contains(key, "..") {
		return errors.New("key must not contain '..'")
	}
	if strings.Contains(key, "//") {
		return errors.New("key must not contain '//'")
	}
	return nil
}

func (a *App) putHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		methodNotAllowed(w, "POST, PUT")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), ioTimeout)
	defer cancel()

	key := strings.TrimPrefix(r.URL.Path, "/put/")
	if err := validateKey(key); err != nil {
		badRequest(w, err.Error())
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "read body: "+err.Error(), http.StatusInternalServerError)
		return
	}
	defer r.Body.Close()

	_, err = a.client.UploadBuffer(ctx, containerName, key, body, nil)
	if err != nil {
		http.Error(w, "upload: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("OK"))
}

func (a *App) getHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, "GET")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), ioTimeout)
	defer cancel()

	key := strings.TrimPrefix(r.URL.Path, "/get/")
	if err := validateKey(key); err != nil {
		badRequest(w, err.Error())
		return
	}

	resp, err := a.client.DownloadStream(ctx, containerName, key, nil)
	if err != nil {
		http.Error(w, "download: "+err.Error(), http.StatusNotFound)
		return
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		http.Error(w, "read: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func (a *App) deleteHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		methodNotAllowed(w, "DELETE")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), ioTimeout)
	defer cancel()

	key := strings.TrimPrefix(r.URL.Path, "/delete/")
	if err := validateKey(key); err != nil {
		badRequest(w, err.Error())
		return
	}

	_, err := a.client.DeleteBlob(ctx, containerName, key, nil)
	if err != nil {
		http.Error(w, "delete: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("DELETED"))
}

func (a *App) touchHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		methodNotAllowed(w, "POST, PUT")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), touchTimeout)
	defer cancel()

	key := strings.TrimPrefix(r.URL.Path, "/touch/")
	if err := validateKey(key); err != nil {
		badRequest(w, err.Error())
		return
	}

	_, err := a.client.UploadBuffer(ctx, containerName, key, []byte{}, nil)
	if err != nil {
		http.Error(w, "touch: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("TOUCHED"))
}

func (a *App) agentsHandler(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), listTimeout)
	defer cancel()

	maxAgeSec := 60
	if v := r.URL.Query().Get("maxAgeSec"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			maxAgeSec = n
		}
	}
	cutoff := time.Now().Add(-time.Duration(maxAgeSec) * time.Second)

	prefix := "agents/"
	pager := a.client.NewListBlobsFlatPager(containerName, &azblob.ListBlobsFlatOptions{
		Prefix: &prefix,
	})

	set := make(map[string]struct{})
	for pager.More() {
		page, err := pager.NextPage(ctx)
		if err != nil {
			http.Error(w, "list: "+err.Error(), http.StatusInternalServerError)
			return
		}
		for _, blob := range page.Segment.BlobItems {
			if blob.Name == nil || blob.Properties.LastModified == nil {
				continue
			}
			name := *blob.Name
			parts := strings.Split(name, "/")
			if len(parts) == 3 && parts[0] == "agents" && parts[2] == "ready" {
				if blob.Properties.LastModified.After(cutoff) {
					set[parts[1]] = struct{}{}
				}
			}
		}
	}

	ids := make([]string, 0, len(set))
	for id := range set {
		ids = append(ids, id)
	}
	sort.Strings(ids)

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(ids)
}

func (a *App) listHandler(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), listTimeout)
	defer cancel()

	prefix := r.URL.Query().Get("prefix")
	if prefix == "" {
		badRequest(w, "prefix required")
		return
	}
	if strings.Contains(prefix, "..") || strings.HasPrefix(prefix, "/") {
		badRequest(w, "invalid prefix")
		return
	}

	pager := a.client.NewListBlobsFlatPager(containerName, &azblob.ListBlobsFlatOptions{
		Prefix: &prefix,
	})

	var names []string
	for pager.More() {
		page, err := pager.NextPage(ctx)
		if err != nil {
			http.Error(w, "list: "+err.Error(), http.StatusInternalServerError)
			return
		}
		for _, it := range page.Segment.BlobItems {
			if it.Name != nil {
				names = append(names, *it.Name)
			}
		}
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(names)
}

func (a *App) healthHandler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("signal-gw alive"))
}

func main() {
	app, err := newApp()
	if err != nil {
		log.Fatal(err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/put/", app.putHandler)
	mux.HandleFunc("/get/", app.getHandler)
	mux.HandleFunc("/delete/", app.deleteHandler)
	mux.HandleFunc("/touch/", app.touchHandler)
	mux.HandleFunc("/agents", app.agentsHandler)
	mux.HandleFunc("/list", app.listHandler)
	mux.HandleFunc("/health", app.healthHandler)

	addr := ":9090"
	log.Printf("Signal Gateway running on %s (account=%s, container=%s)", addr, accountName, containerName)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}
