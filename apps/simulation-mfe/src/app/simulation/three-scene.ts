import {
  AdditiveBlending,
  BufferAttribute,
  BufferGeometry,
  CanvasTexture,
  Color,
  Line,
  LineBasicMaterial,
  PerspectiveCamera,
  Points,
  PointsMaterial,
  Raycaster,
  Scene,
  Sprite,
  SpriteMaterial,
  Texture,
  Vector2,
  WebGLRenderer
} from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/**
 * Builds a soft radial-gradient sprite procedurally on a 2D canvas.
 * Without this, three.js renders {@link Points} as raw GL square pixels.
 * The result is a circular star-like sprite usable as a {@link CanvasTexture}.
 */
function createStarSprite(size = 64): Texture {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d')!;
  const cx = size / 2;
  // Pure-white radial gradient — vertex color (set per particle) supplies the
  // actual hue. A coloured sprite would tint everything and wash out the
  // per-vertex palette under additive blending.
  const gradient = ctx.createRadialGradient(cx, cx, 0, cx, cx, cx);
  gradient.addColorStop(0.0, 'rgba(255, 255, 255, 1.0)');
  gradient.addColorStop(0.3, 'rgba(255, 255, 255, 0.7)');
  gradient.addColorStop(0.7, 'rgba(255, 255, 255, 0.18)');
  gradient.addColorStop(1.0, 'rgba(255, 255, 255, 0.0)');
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, size, size);
  const tex = new CanvasTexture(canvas);
  tex.needsUpdate = true;
  return tex;
}

/** Camera-facing ring used to highlight the currently selected body. */
function createRingSprite(size = 64): Texture {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d')!;
  const cx = size / 2;
  ctx.clearRect(0, 0, size, size);
  ctx.lineWidth = Math.max(2, size / 24);
  ctx.strokeStyle = 'rgba(167, 139, 250, 0.95)';   // purple to match trail
  ctx.beginPath();
  ctx.arc(cx, cx, cx * 0.78, 0, Math.PI * 2);
  ctx.stroke();
  // Soft inner glow so the ring blooms under additive blending.
  ctx.lineWidth = Math.max(1, size / 48);
  ctx.strokeStyle = 'rgba(216, 180, 254, 0.55)';
  ctx.beginPath();
  ctx.arc(cx, cx, cx * 0.65, 0, Math.PI * 2);
  ctx.stroke();
  const tex = new CanvasTexture(canvas);
  tex.needsUpdate = true;
  return tex;
}

/**
 * Minimal Three.js scene for rendering N point bodies.
 *
 * <p>Uses {@link Points} + {@link BufferGeometry} for instanced rendering.
 * Position updates rewrite the {@code position} attribute in place, marking
 * it dirty so the GPU re-uploads — this is the canonical fast path for
 * tens of thousands of points at 60 Hz.
 *
 * <p>The point material uses a procedural radial-gradient sprite for
 * soft circular stars (Sem 2 will replace this with a fragment shader
 * that varies size and color by velocity / mass / proximity).
 */
export class NBodyScene {
  private readonly scene = new Scene();
  private readonly camera: PerspectiveCamera;
  private readonly renderer: WebGLRenderer;
  private readonly controls: OrbitControls;
  private readonly sprite: Texture;
  private points: Points | null = null;
  private positionAttr: BufferAttribute | null = null;
  private colorAttr: BufferAttribute | null = null;

  private animationHandle = 0;
  private resizeHandler: () => void;

  // ===== Selection / trail (click-to-follow) =====
  /** Trail capacity: 256 points ≈ 4 s at 60 Hz. */
  private static readonly TRAIL_CAPACITY = 256;
  private selectedIndex: number | null = null;
  private trailPos: Float32Array | null = null;
  private trailLen = 0;
  private trailLine: Line | null = null;
  private trailGeom: BufferGeometry | null = null;
  private haloSprite: Sprite | null = null;
  private haloTex: Texture | null = null;
  /** Reused raycaster + scratch NDC vector — picking is a hot path. */
  private readonly raycaster = new Raycaster();
  private readonly ndc = new Vector2();

  constructor(canvas: HTMLCanvasElement) {
    this.renderer = new WebGLRenderer({ canvas, antialias: true, alpha: false });
    this.renderer.setPixelRatio(window.devicePixelRatio);
    this.scene.background = new Color(0x050810);
    this.sprite = createStarSprite();

    this.camera = new PerspectiveCamera(60, 1, 0.01, 100);
    this.camera.position.set(0, 0, 2.5);
    this.camera.lookAt(0, 0, 0);

    // OrbitControls — drag rotates, scroll zooms, right-drag pans.
    // Damping gives a smoother feel; rotateSpeed slowed slightly for precision.
    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.rotateSpeed = 0.7;
    this.controls.zoomSpeed = 0.8;
    this.controls.panSpeed = 0.6;
    this.controls.minDistance = 0.05;
    this.controls.maxDistance = 50;
    this.controls.target.set(0, 0, 0);

    this.resizeHandler = () => this.handleResize();
    window.addEventListener('resize', this.resizeHandler);
    this.handleResize();
  }

  /**
   * Replaces the geometry with N points. Call once per N-change; subsequent
   * frames at the same N should call {@link updatePositions} instead.
   */
  setBodies(positionsXYZ: Float32Array): void {
    const n = positionsXYZ.length / 3;

    if (this.points) {
      this.scene.remove(this.points);
      this.points.geometry.dispose();
      (this.points.material as PointsMaterial).dispose();
    }
    // N (re)allocation invalidates any pending body selection.
    this.setSelectedBody(null);

    const geometry = new BufferGeometry();
    const posAttr = new BufferAttribute(positionsXYZ, 3);
    posAttr.setUsage(35048 /* DynamicDrawUsage */);
    geometry.setAttribute('position', posAttr);

    // Per-vertex color attribute. Updated each frame from the radial palette
    // so cluster structure (dense core vs diffuse halo) becomes visible.
    const colors = new Float32Array(3 * n);
    const colAttr = new BufferAttribute(colors, 3);
    colAttr.setUsage(35048 /* DynamicDrawUsage */);
    geometry.setAttribute('color', colAttr);

    const material = new PointsMaterial({
      color: 0xffffff,        // baseline; per-vertex colors multiply on top
      vertexColors: true,
      map: this.sprite,
      size: 0.05,
      sizeAttenuation: true,
      transparent: true,
      opacity: 0.85,
      blending: AdditiveBlending,
      depthWrite: false
    });

    this.points = new Points(geometry, material);
    this.scene.add(this.points);
    this.positionAttr = posAttr;
    this.colorAttr = colAttr;

    this.recomputeColors(positionsXYZ);
    console.info(`[NBodyScene] geometry initialized for N=${n}`);
  }

  /**
   * Fast path: copy new positions into the existing buffer and mark dirty.
   * Assumes {@code positionsXYZ.length === 3·N} matches current geometry.
   */
  updatePositions(positionsXYZ: Float32Array): void {
    if (!this.positionAttr) {
      this.setBodies(positionsXYZ);
      return;
    }
    if (positionsXYZ.length !== this.positionAttr.array.length) {
      // N changed — rebuild geometry.
      this.setBodies(positionsXYZ);
      return;
    }
    (this.positionAttr.array as Float32Array).set(positionsXYZ);
    this.positionAttr.needsUpdate = true;
    this.recomputeColors(positionsXYZ);
    this.advanceSelection(positionsXYZ);
  }

  // ============================================================
  // Click-to-follow: picking + trail + halo
  // ============================================================

  /**
   * Hit-test the points cloud at the given client-space cursor coords.
   * Returns the index of the nearest particle within picking threshold,
   * or null if nothing close enough.
   */
  pickAt(clientX: number, clientY: number): number | null {
    if (!this.points) return null;
    const rect = this.renderer.domElement.getBoundingClientRect();
    this.ndc.x =  ((clientX - rect.left) / rect.width)  * 2 - 1;
    this.ndc.y = -((clientY - rect.top)  / rect.height) * 2 + 1;
    this.raycaster.setFromCamera(this.ndc, this.camera);
    // Threshold scaled to point sprite size — empirically generous so the user
    // does not have to land precisely on the few-pixel sprite centre.
    if (this.raycaster.params.Points) {
      this.raycaster.params.Points.threshold = 0.04;
    }
    const hits = this.raycaster.intersectObject(this.points);
    if (hits.length === 0) return null;
    return hits[0].index ?? null;
  }

  getSelectedIndex(): number | null { return this.selectedIndex; }

  /** Returns [x,y,z] of the selected body in the most recent rendered frame. */
  getSelectedPosition(): [number, number, number] | null {
    if (this.selectedIndex === null || !this.positionAttr) return null;
    const arr = this.positionAttr.array as Float32Array;
    const i = this.selectedIndex * 3;
    if (i + 2 >= arr.length) return null;
    return [arr[i], arr[i + 1], arr[i + 2]];
  }

  setSelectedBody(idx: number | null): void {
    this.selectedIndex = idx;
    this.trailLen = 0;
    if (idx === null) {
      this.disposeSelectionVisuals();
      return;
    }
    if (!this.trailLine) this.createSelectionVisuals();
    if (this.trailGeom) this.trailGeom.setDrawRange(0, 0);
  }

  private createSelectionVisuals(): void {
    // ---- Trail (Line) ----
    this.trailPos = new Float32Array(3 * NBodyScene.TRAIL_CAPACITY);
    this.trailGeom = new BufferGeometry();
    const trailAttr = new BufferAttribute(this.trailPos, 3);
    trailAttr.setUsage(35048 /* DynamicDrawUsage */);
    this.trailGeom.setAttribute('position', trailAttr);
    this.trailGeom.setDrawRange(0, 0);
    const trailMat = new LineBasicMaterial({
      color: 0xa78bfa,
      transparent: true, opacity: 0.85,
      blending: AdditiveBlending, depthWrite: false
    });
    this.trailLine = new Line(this.trailGeom, trailMat);
    this.scene.add(this.trailLine);

    // ---- Halo sprite at the selected body (always camera-facing) ----
    if (!this.haloTex) this.haloTex = createRingSprite();
    const haloMat = new SpriteMaterial({
      map: this.haloTex, color: 0xffffff,
      transparent: true, depthWrite: false, depthTest: false,
      blending: AdditiveBlending
    });
    this.haloSprite = new Sprite(haloMat);
    this.haloSprite.scale.set(0.12, 0.12, 0.12);
    this.scene.add(this.haloSprite);
  }

  private disposeSelectionVisuals(): void {
    if (this.trailLine) {
      this.scene.remove(this.trailLine);
      this.trailGeom?.dispose();
      (this.trailLine.material as LineBasicMaterial).dispose();
      this.trailLine = null;
      this.trailGeom = null;
      this.trailPos = null;
    }
    if (this.haloSprite) {
      this.scene.remove(this.haloSprite);
      (this.haloSprite.material as SpriteMaterial).dispose();
      this.haloSprite = null;
    }
  }

  /** Each frame: append the selected body's current position to the trail
   *  (sliding window once full) and move the halo. If N changed and the
   *  index is no longer valid, drop the selection. */
  private advanceSelection(positionsXYZ: Float32Array): void {
    if (this.selectedIndex === null || !this.trailGeom || !this.trailPos) return;
    const i = this.selectedIndex * 3;
    if (i + 2 >= positionsXYZ.length) { this.setSelectedBody(null); return; }
    const x = positionsXYZ[i], y = positionsXYZ[i + 1], z = positionsXYZ[i + 2];

    const cap = NBodyScene.TRAIL_CAPACITY;
    if (this.trailLen < cap) {
      const w = this.trailLen * 3;
      this.trailPos[w] = x; this.trailPos[w + 1] = y; this.trailPos[w + 2] = z;
      this.trailLen++;
    } else {
      // Slide left by one vertex (3 floats), append at the tail.
      this.trailPos.copyWithin(0, 3);
      const w = (cap - 1) * 3;
      this.trailPos[w] = x; this.trailPos[w + 1] = y; this.trailPos[w + 2] = z;
    }
    const trailAttr = this.trailGeom.getAttribute('position') as BufferAttribute;
    trailAttr.needsUpdate = true;
    this.trailGeom.setDrawRange(0, this.trailLen);

    if (this.haloSprite) this.haloSprite.position.set(x, y, z);
  }

  /**
   * Color each particle by its distance from the cluster centre.
   * Palette is intentionally saturated so the structure is visible under
   * additive blending (the typical "blue-white-blue" pastel washes out).
   *
   *   r → 0    : saturated amber       (core, dense)
   *   r → med  : warm white            (mid halo)
   *   r → max  : saturated cyan-blue   (halo + escapers)
   *
   * Normalisation uses the **median radius × 1.5** instead of max, so a few
   * escapers don't squash all interior particles into the same colour band.
   * Robust statistics matter here because evaporation is constant.
   *
   * Cost: O(N log N) for the median; sort is on a temp buffer, no allocations
   * in steady state once the size stabilises.
   */
  private radiiBuf: Float64Array | null = null;

  private recomputeColors(positionsXYZ: Float32Array): void {
    if (!this.colorAttr) return;
    const colors = this.colorAttr.array as Float32Array;
    const n = positionsXYZ.length / 3;

    if (!this.radiiBuf || this.radiiBuf.length !== n) {
      this.radiiBuf = new Float64Array(n);
    }
    const radii = this.radiiBuf;

    for (let i = 0; i < n; i++) {
      const x = positionsXYZ[3 * i];
      const y = positionsXYZ[3 * i + 1];
      const z = positionsXYZ[3 * i + 2];
      radii[i] = Math.sqrt(x * x + y * y + z * z);
    }

    // Median radius (sorts a copy to avoid mutating the order).
    const sorted = Array.from(radii).sort((a, b) => a - b);
    const median = sorted[Math.floor(n / 2)] || 1;
    const refR = Math.max(1e-9, median * 1.5);   // colour reaches "edge" at 1.5× median

    for (let i = 0; i < n; i++) {
      const t = Math.min(1, radii[i] / refR);    // 0..1, clamped
      // Three-stop palette via two linear interps, t ∈ [0, 0.5, 1]
      let R: number, G: number, B: number;
      if (t < 0.5) {
        const k = t / 0.5;                       // 0..1 in first half
        R = lerp(255, 255, k); G = lerp(165,  235, k); B = lerp( 70, 200, k);
      } else {
        const k = (t - 0.5) / 0.5;               // 0..1 in second half
        R = lerp(255,  90, k); G = lerp(235, 170, k); B = lerp(200, 255, k);
      }
      colors[3 * i    ] = R / 255;
      colors[3 * i + 1] = G / 255;
      colors[3 * i + 2] = B / 255;
    }
    this.colorAttr.needsUpdate = true;
  }

  /** Begin the render loop. Calls {@code onFrame} once per VSync. */
  start(onFrame: () => void): void {
    const tick = () => {
      onFrame();
      this.controls.update();   // OrbitControls damping needs per-frame update
      this.renderer.render(this.scene, this.camera);
      this.animationHandle = requestAnimationFrame(tick);
    };
    this.animationHandle = requestAnimationFrame(tick);
  }

  stop(): void {
    if (this.animationHandle) {
      cancelAnimationFrame(this.animationHandle);
      this.animationHandle = 0;
    }
  }

  /** Reset the camera to the default position (front view, slight tilt). */
  resetCamera(): void {
    this.camera.position.set(0, 0, 2.5);
    this.controls.target.set(0, 0, 0);
    this.controls.update();
  }

  dispose(): void {
    this.stop();
    window.removeEventListener('resize', this.resizeHandler);
    this.controls.dispose();
    this.disposeSelectionVisuals();
    this.haloTex?.dispose();
    if (this.points) {
      this.scene.remove(this.points);
      this.points.geometry.dispose();
      (this.points.material as PointsMaterial).dispose();
    }
    this.sprite.dispose();
    this.renderer.dispose();
  }

  private handleResize(): void {
    const canvas = this.renderer.domElement;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (w === 0 || h === 0) return;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }
}
