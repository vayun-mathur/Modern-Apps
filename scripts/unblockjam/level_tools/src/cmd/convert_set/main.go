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

	result := map[string]interface{}{
		"name": "Example Pack",
		"colors": map[string]string{
			"primary":			  "0x00000000",
			"secondary":		  "0x00000000",
			"tertiary":		      "0x00000000",
			"background":		  "0x00000000",
			"surface":			  "0x00000000",
			"primaryContainer":   "0x00000000",
			"secondaryContainer": "0x00000000",
		},
		"levels": levels,
	}

	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(result); err != nil {
		return fmt.Errorf("encoding JSON: %w", err)
	}
	return nil
}
