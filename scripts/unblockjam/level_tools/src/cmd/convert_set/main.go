package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"

	"rush_conv"
)

type Colors struct {
	Primary            string `json:"primary"`
	Secondary          string `json:"secondary"`
	Tertiary           string `json:"tertiary"`
	Background         string `json:"background"`
	Surface            string `json:"surface"`
	PrimaryContainer   string `json:"primaryContainer"`
	SecondaryContainer string `json:"secondaryContainer"`
}

type Pack struct {
	Name   string            `json:"name"`
	Colors Colors            `json:"colors"`
	Levels []rush_conv.Level `json:"levels"`
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	var levels []rush_conv.Level

	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		line := scanner.Text()
		parts := strings.Fields(line)
		if len(parts) != 3 {
			return fmt.Errorf("expected exactly 3 parts in line %q, got %d", line, len(parts))
		}

		optimal, err := strconv.Atoi(parts[2])
		if err != nil {
			return fmt.Errorf("converting optimal value to integer in line %q: %w", line, err)
		}

		puzzle := parts[1]
		board := rush_conv.Convert(puzzle)

		level := rush_conv.Level{
			Board: board,
			ID:    rush_conv.BoardID(board),
			Moves: optimal,
		}

		levels = append(levels, level)
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("reading stdin: %w", err)
	}

	result := Pack{
		Name: "Example Pack",
		Colors: Colors{
			Primary:            "0x00000000",
			Secondary:          "0x00000000",
			Tertiary:           "0x00000000",
			Background:         "0x00000000",
			Surface:            "0x00000000",
			PrimaryContainer:   "0x00000000",
			SecondaryContainer: "0x00000000",
		},
		Levels: levels,
	}

	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(result); err != nil {
		return fmt.Errorf("encoding JSON: %w", err)
	}
	return nil
}
