#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
SVG = "{http://www.w3.org/2000/svg}"

CLASS_STYLES = {
    "outline": {"stroke": "#c6ab67", "stroke-width": "10", "fill": "none", "stroke-linecap": "round", "stroke-linejoin": "round"},
    "thin": {"stroke": "#4f6f4c", "stroke-width": "6", "fill": "none", "stroke-linecap": "round", "stroke-linejoin": "round", "opacity": ".95"},
    "poly": {"stroke": "#233323", "stroke-width": "5", "stroke-linejoin": "round"},
    "goldStroke": {"stroke": "#a78d56", "stroke-width": "5", "stroke-linejoin": "round"},
    "eye": {"fill": "#d9c86d", "stroke": "#7e6b2e", "stroke-width": "3"},
}

GRADIENTS = {
    "url(#gold)": "#c6ab67",
    "url(#shieldGrad)": "#0d1110",
}


def attr(element, name):
    return element.attrib.get(name)


def merged_style(element):
    style = {}
    class_name = attr(element, "class")
    if class_name:
        style.update(CLASS_STYLES.get(class_name, {}))
    for key, value in element.attrib.items():
        if key not in {"class", "d", "points", "cx", "cy", "r"}:
            style[key] = value
    return style


def clean_color(value):
    if not value or value == "none":
        return None
    return GRADIENTS.get(value, value)


def path_attrs(style):
    result = {}
    fill = clean_color(style.get("fill"))
    stroke = clean_color(style.get("stroke"))
    opacity = style.get("opacity")
    if fill:
        result[f"{{{ANDROID}}}fillColor"] = fill
    else:
        result[f"{{{ANDROID}}}fillColor"] = "@android:color/transparent"
    if stroke:
        result[f"{{{ANDROID}}}strokeColor"] = stroke
        result[f"{{{ANDROID}}}strokeWidth"] = style.get("stroke-width", "1")
    if style.get("stroke-linecap"):
        result[f"{{{ANDROID}}}strokeLineCap"] = style["stroke-linecap"]
    if style.get("stroke-linejoin"):
        result[f"{{{ANDROID}}}strokeLineJoin"] = style["stroke-linejoin"]
    if opacity:
        if fill:
            result[f"{{{ANDROID}}}fillAlpha"] = opacity
        if stroke:
            result[f"{{{ANDROID}}}strokeAlpha"] = opacity
    return result


def polygon_to_path(points):
    pairs = [p for p in re.split(r"\s+", points.strip()) if p]
    if not pairs:
        return ""
    coords = [tuple(p.split(",", 1)) for p in pairs]
    first = coords[0]
    rest = coords[1:]
    return "M" + first[0] + "," + first[1] + "".join(" L" + x + "," + y for x, y in rest) + " Z"


def circle_to_path(cx, cy, r):
    cx = float(cx)
    cy = float(cy)
    r = float(r)
    return (
        f"M{cx - r:.3f},{cy:.3f} "
        f"a{r:.3f},{r:.3f} 0,1 0,{2 * r:.3f},0 "
        f"a{r:.3f},{r:.3f} 0,1 0,{-2 * r:.3f},0"
    )


def convert(svg_path, out_path):
    ET.register_namespace("android", ANDROID)
    root = ET.parse(svg_path).getroot()
    vector = ET.Element(
        "vector",
        {
            f"{{{ANDROID}}}width": "108dp",
            f"{{{ANDROID}}}height": "108dp",
            f"{{{ANDROID}}}viewportWidth": "1024",
            f"{{{ANDROID}}}viewportHeight": "1024",
        },
    )

    for element in root.iter():
        tag = element.tag
        style = merged_style(element)
        data = None
        if tag == SVG + "path":
            data = attr(element, "d")
        elif tag == SVG + "polygon":
            data = polygon_to_path(attr(element, "points") or "")
        elif tag == SVG + "circle":
            data = circle_to_path(attr(element, "cx"), attr(element, "cy"), attr(element, "r"))
        if not data:
            continue

        path = ET.SubElement(vector, "path", path_attrs(style))
        path.set(f"{{{ANDROID}}}pathData", re.sub(r"\s+", " ", data.strip()))

    text = ET.tostring(vector, encoding="unicode")
    out_path.write_text(text.replace("><", ">\n<"), encoding="utf-8")


if __name__ == "__main__":
    convert(Path(sys.argv[1]), Path(sys.argv[2]))
