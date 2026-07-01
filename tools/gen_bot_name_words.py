"""Regenerate src/main/resources/bot/name_words.txt for BotNameGenerator.

Source: Norvig count_1w.txt (web-corpus frequency rank) filtered by:
  - ascii [a-z] only
  - drop English stopwords/function words (embedded set below)
  - drop LDNOOBW 'en' profanity (exact match + >=4-char substring of the worst)
Keep the top 4000 combinable (len 3-6) + 1500 standalone (len 7-8) by frequency.

Run on this box with the Windows Python launcher:  py tools/gen_bot_name_words.py
Hand-editing the output file afterwards is fine (read verbatim at startup).
"""
import os
import urllib.request

OUT = os.path.join(os.path.dirname(__file__), "..",
                   "src", "main", "resources", "bot", "name_words.txt")
COMB_N, STAND_N = 4000, 1500


def get(url):
    return urllib.request.urlopen(url, timeout=30).read().decode("utf-8", "ignore")


STOP = set('''a an the and or but nor for so yet of in on at to by from with into onto upon over under
this that these those it its is are was were be been being am has have had do does did will would
can could shall should may might must not no yes all any some each every few many much more most
other such only own same than too very who whom whose which what where when why how here there now
then once also just even still about above below between through during before after again out off
up down our your their his her my mine ours yours them they we you he she him me us i as if because
while until unless although though whether either neither both per via etc thus hence whereas'''.split())


def main():
    ordered, seen = [], set()
    for line in get("https://norvig.com/ngrams/count_1w.txt").splitlines():
        p = line.split("\t")
        if len(p) == 2:
            w = p[0].strip().lower()
            if w.isalpha() and w.isascii() and w not in seen:
                seen.add(w)
                ordered.append(w)

    prof = {w.strip().lower() for w in get(
        "https://raw.githubusercontent.com/LDNOOBW/"
        "List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/en").split() if w.strip()}
    hard = [w for w in prof if w.isalpha() and len(w) >= 4]

    def ok(w):
        return w not in STOP and w not in prof and not any(h in w for h in hard)

    comb = [w for w in ordered if 3 <= len(w) <= 6 and ok(w)][:COMB_N]
    stand = [w for w in ordered if 7 <= len(w) <= 8 and ok(w)][:STAND_N]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="ascii", newline="\n") as f:
        f.write("# Generic name-word pool for BotNameGenerator. Sections: [combinable] (len 3-6,\n")
        f.write("# used for two-word CamelCase combos) then [standalone] (len 7-8, single words).\n")
        f.write("# Regenerate with tools/gen_bot_name_words.py. TUNABLE: hand-edit to drop odd/\n")
        f.write("# sensitive words (religion/nationality); the file is read verbatim at startup.\n")
        f.write("[combinable]\n" + "\n".join(comb) + "\n")
        f.write("[standalone]\n" + "\n".join(stand) + "\n")
    print("wrote %s  combinable=%d standalone=%d  pairs~%d" %
          (os.path.normpath(OUT), len(comb), len(stand), len(comb) ** 2))


if __name__ == "__main__":
    main()
