## Puzzle converter
Converts puzzles from [Michael Fogleman's format](https://www.michaelfogleman.com/rush/#DatabaseFormat) to unblockjam's JSON format.

> [!IMPORTANT]
> The output level pack will have a default pack name and all colors set to `0`. You will need to manually set these in the resulting file.

## Usage
`Stdin`: Puzzles in [this format](https://www.michaelfogleman.com/rush/#DatabaseFormat).
`Stdout`: Converted level pack.

## Example
You can find a large set of puzzles [here](https://www.michaelfogleman.com/rush/#DatabaseDownload).

```sh
cd src
cat example_levels.txt | go run cmd/convert_set > example_pack.json
```

## Level IDs
Level IDs are automatically generated. They are a 96-bit slice of the board's sha256 representation encoded in URL-safe base64. I'm pretty sure this should suffice for avoiding collisions. There's also a version number (`n-`) prefixed onto each ID for future-proofing.