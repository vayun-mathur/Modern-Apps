package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"

	"rush_conv"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	data, err := io.ReadAll(os.Stdin)
	if err != nil {
		return fmt.Errorf("reading stdin: %w", err)
	}

	var board rush_conv.Board
	if err := json.Unmarshal(data, &board); err != nil {
		return fmt.Errorf("parsing board JSON: %w\ninput should be a single board in JSON format", err)
	}

	fmt.Println(rush_conv.BoardID(board))

	return nil
}