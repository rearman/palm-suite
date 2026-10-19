// Command palmsync runs the Palm Suite sync server and manages its access tokens.
package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

const defaultData = "/var/lib/palmsync"

// errUsage marks a bad command line. main prints the usage and exits 2.
var errUsage = errors.New("usage")

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "serve":
		err = runServe(os.Args[2:])
	case "token":
		err = runToken(os.Args[2:])
	default:
		err = errUsage
	}
	if errors.Is(err, errUsage) {
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "palmsync: %v\n", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `usage: palmsync <command> [flags]

  serve                 run the sync server (-data DIR, -listen ADDR)
  token add NAME        create a token and print it once (-data DIR)
  token list            list the token names (-data DIR)
  token revoke NAME     remove a token (-data DIR)
`)
}

func runServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	dir := fs.String("data", defaultData, "data directory")
	listen := fs.String("listen", "127.0.0.1:8640", "address to listen on")
	if err := fs.Parse(args); err != nil {
		return err
	}

	st, err := OpenStore(*dir)
	if err != nil {
		return err
	}
	srv := &http.Server{
		Addr:              *listen,
		Handler:           NewServer(st, *dir).Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	drained := make(chan struct{})
	go func() {
		defer close(drained)
		<-ctx.Done()
		shutCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutCtx); err != nil {
			log.Printf("palmsync: shutdown: %v", err)
		}
	}()

	log.Printf("palmsync: listening on %s", *listen)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("palmsync: %v", err)
	}
	<-drained
	return st.Close()
}

func runToken(args []string) error {
	if len(args) < 1 {
		return errUsage
	}
	switch args[0] {
	case "add":
		return tokenAdd(args[1:])
	case "list":
		return tokenList(args[1:])
	case "revoke":
		return tokenRevoke(args[1:])
	}
	return errUsage
}

func tokenAdd(args []string) error {
	fs := flag.NewFlagSet("token add", flag.ContinueOnError)
	dir := fs.String("data", defaultData, "data directory")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errUsage
	}
	name := fs.Arg(0)
	if name == "" {
		return errors.New("token name must not be empty")
	}

	ts, err := LoadTokens(*dir)
	if err != nil {
		return err
	}
	for _, t := range ts {
		if t.Name == name {
			return fmt.Errorf("token %q already exists", name)
		}
	}

	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	sum := sha256.Sum256([]byte(token))
	ts = append(ts, Token{Name: name, Hash: hex.EncodeToString(sum[:])})
	if err := SaveTokens(*dir, ts); err != nil {
		return err
	}
	fmt.Printf("Token for %s (shown only this time): %s\n", name, token)
	return nil
}

func tokenList(args []string) error {
	fs := flag.NewFlagSet("token list", flag.ContinueOnError)
	dir := fs.String("data", defaultData, "data directory")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 0 {
		return errUsage
	}

	ts, err := LoadTokens(*dir)
	if err != nil {
		return err
	}
	for _, t := range ts {
		fmt.Println(t.Name)
	}
	return nil
}

func tokenRevoke(args []string) error {
	fs := flag.NewFlagSet("token revoke", flag.ContinueOnError)
	dir := fs.String("data", defaultData, "data directory")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errUsage
	}
	name := fs.Arg(0)

	ts, err := LoadTokens(*dir)
	if err != nil {
		return err
	}
	kept := make([]Token, 0, len(ts))
	for _, t := range ts {
		if t.Name != name {
			kept = append(kept, t)
		}
	}
	if len(kept) == len(ts) {
		return fmt.Errorf("no token named %q", name)
	}
	return SaveTokens(*dir, kept)
}
