# Pavlova - Documentation Index

**Complete Technical Foundation for Master's Thesis Prototype**

---

## 📋 Quick Navigation

This documentation package provides everything needed to implement Pavlova, an on-device screen-safety system for Android, as a Master's thesis project.

### Core Documentation

| Document | Purpose | Audience | Length |
|----------|---------|----------|--------|
| **[README.md](README.md)** | Project overview, quick start | Everyone | 5 min read |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | Complete system design | Developers, Reviewers | 30 min read |
| **[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)** | Step-by-step development guide | Developers | 45 min read |
| **[RUST_LIBRARY_SPEC.md](RUST_LIBRARY_SPEC.md)** | Rust module specifications | Rust developers | 25 min read |
| **[PRIVACY_SECURITY.md](PRIVACY_SECURITY.md)** | Privacy policy & security | All stakeholders | 20 min read |
| **[EVALUATION.md](EVALUATION.md)** | Testing methodology | Researchers | 30 min read |
| **[THESIS_OUTLINE.md](THESIS_OUTLINE.md)** | Complete academic structure | Thesis writers | 40 min read |
| **[PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)** | Directory layout & setup | Developers | 15 min read |

---

## 🎯 Reading Paths

### For Implementation

**Path: Build the System**
1. [README.md](README.md) - Understand what you're building
2. [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) - Set up development environment
3. [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) - Follow step-by-step guide
4. [RUST_LIBRARY_SPEC.md](RUST_LIBRARY_SPEC.md) - Implement Rust components
5. [ARCHITECTURE.md](ARCHITECTURE.md) - Reference for design decisions

**Time Estimate**: 8-12 weeks of development

---

### For Thesis Writing

**Path: Academic Documentation**
1. [THESIS_OUTLINE.md](THESIS_OUTLINE.md) - Complete thesis structure
2. [ARCHITECTURE.md](ARCHITECTURE.md) - System Design chapter
3. [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) - Implementation chapter
4. [EVALUATION.md](EVALUATION.md) - Evaluation methodology
5. [PRIVACY_SECURITY.md](PRIVACY_SECURITY.md) - Ethics & privacy discussion

**Time Estimate**: 3-4 months of writing (with implementation complete)

---

### For Review/Evaluation

**Path: Understanding the Contribution**
1. [README.md](README.md) - Executive overview
2. [ARCHITECTURE.md](ARCHITECTURE.md) - Technical approach
3. [EVALUATION.md](EVALUATION.md) - Results and analysis
4. [THESIS_OUTLINE.md](THESIS_OUTLINE.md) - Academic contribution
5. [PRIVACY_SECURITY.md](PRIVACY_SECURITY.md) - Ethical considerations

**Time Estimate**: 1-2 hours total

---

## 📊 What's Included

### 1. Architecture & Design

**[ARCHITECTURE.md](ARCHITECTURE.md)** provides:
- High-level component diagram
- Data flow pipeline with timing targets
- Kotlin ↔ Rust JNI interface design
- Permission model and consent flows
- Privacy-preserving logging strategy
- Performance optimization approach
- Security threat model
- Design alternatives considered

**Key Takeaways**:
- Hybrid Kotlin-Rust architecture for performance
- MediaProjection for screen capture (Android 14+ compliant)
- On-device ML with TFLite for privacy
- TYPE_APPLICATION_OVERLAY for selective blurring

---

### 2. Implementation Guide

**[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)** provides:
- Complete Gradle configuration
- Kotlin service implementations:
  - `ScreenCaptureService`
  - `OverlayManager`
  - `FrameProcessor`
  - `PermissionManager`
- Rust library scaffolding:
  - JNI bridge
  - ML inference engine
  - Image processing effects
- Phase-by-phase development roadmap
- Code examples ready to copy/adapt

**Key Takeaways**:
- Real, production-ready code (not pseudocode)
- Handles Android 14+ per-session consent
- Memory pooling for performance
- Adaptive frame rate for battery

---

### 3. Rust Specifications

**[RUST_LIBRARY_SPEC.md](RUST_LIBRARY_SPEC.md)** provides:
- Complete module structure
- YUV→RGB conversion implementation
- Fast resize using `fast_image_resize`
- Gaussian blur and pixelation algorithms
- Policy engine for rule evaluation
- Memory pool for frame buffers
- Performance metrics collection
- JNI integration examples
- Cargo.toml with all features
- Benchmarking setup

**Key Takeaways**:
- SIMD optimizations for ARM NEON
- Zero-copy aspirations with DirectByteBuffer
- < 90ms total pipeline target
- Reusable buffer pool to avoid allocations

---

### 4. Privacy & Security

**[PRIVACY_SECURITY.md](PRIVACY_SECURITY.md)** provides:
- Privacy-first design principles
- Complete permission consent flows
- User-facing privacy dashboard
- Data minimization strategy (no screenshots!)
- Security measures (model integrity, encryption)
- Ethical considerations for thesis
- Privacy policy template
- Accessibility/NotificationListener transparency

**Key Takeaways**:
- All processing on-device, no cloud
- Only timestamps + categories logged (< 1MB/month)
- User control: view, export, delete all data
- Optional services clearly explained

---

### 5. Evaluation Methodology

**[EVALUATION.md](EVALUATION.md)** provides:
- Performance benchmarking strategy
- Test device tiers (high/mid/low-end)
- ML accuracy evaluation approach
- Battery impact measurement
- User experience study design
- Privacy compliance testing
- Automated data collection scripts
- Expected results and hypotheses
- Complete thesis evaluation chapter structure

**Key Takeaways**:
- Target: < 100ms latency, > 90% accuracy
- Battery: < 10% drain/hour
- Usability: SUS score > 70
- Comprehensive testing across 3 device tiers

---

### 6. Thesis Structure

**[THESIS_OUTLINE.md](THESIS_OUTLINE.md)** provides:
- Complete chapter-by-chapter outline
- Abstract template (250-300 words)
- Introduction with research questions
- Background & related work structure
- System design chapter (references ARCHITECTURE.md)
- Implementation chapter (references IMPLEMENTATION_PLAN.md)
- Evaluation chapter (references EVALUATION.md)
- Discussion: limitations, ethics, future work
- Conclusion with contributions
- Bibliography structure
- Appendices checklist
- 8-month timeline

**Key Takeaways**:
- Ready-to-fill thesis template
- All technical content already documented
- Clear research contributions
- Ethical analysis included

---

### 7. Project Setup

**[PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)** provides:
- Complete directory tree
- Step-by-step setup instructions
- Development workflow (build, test, debug)
- Configuration options
- ML model integration guide
- Performance optimization tips
- Troubleshooting common issues
- Contributing guidelines

**Key Takeaways**:
- Reproducible setup from scratch
- Works on macOS, Linux, Windows
- All prerequisites listed
- Common pitfalls documented

---

## 🎓 Academic Contribution

### Research Questions Addressed

**RQ1**: Can on-device ML achieve real-time content classification (< 100ms)?  
**Documented in**: EVALUATION.md, THESIS_OUTLINE.md (Chapter 5)

**RQ2**: What is the accuracy-performance tradeoff?  
**Documented in**: EVALUATION.md (Section 5.3), ARCHITECTURE.md (Section 3.2)

**RQ3**: How significant is the battery impact?  
**Documented in**: EVALUATION.md (Section 5.4)

---

### Contributions

1. **System Design**: Novel privacy-preserving architecture  
   **Documented in**: ARCHITECTURE.md, THESIS_OUTLINE.md (Chapter 3)

2. **Implementation**: Functional Kotlin-Rust hybrid prototype  
   **Documented in**: IMPLEMENTATION_PLAN.md, RUST_LIBRARY_SPEC.md, THESIS_OUTLINE.md (Chapter 4)

3. **Evaluation**: Comprehensive performance analysis  
   **Documented in**: EVALUATION.md, THESIS_OUTLINE.md (Chapter 5)

4. **Ethics**: Privacy and parental control considerations  
   **Documented in**: PRIVACY_SECURITY.md, THESIS_OUTLINE.md (Chapter 6)

5. **Artifact**: Open-source release for reproducibility  
   **Documented in**: README.md, PROJECT_STRUCTURE.md

---

## 🛠️ Implementation Checklist

### Phase 1: Setup (Week 1-2)
- [ ] Install Android Studio, Rust, NDK
- [ ] Clone/create project structure
- [ ] Configure Gradle build system
- [ ] Set up Rust cross-compilation
- [ ] Verify build pipeline works

### Phase 2: Core Android (Week 3-5)
- [ ] Implement `PermissionManager`
- [ ] Implement `ScreenCaptureService`
- [ ] Implement `OverlayManager`
- [ ] Build basic UI with Compose
- [ ] Test on physical device

### Phase 3: Rust Library (Week 6-8)
- [ ] JNI bridge setup
- [ ] Image conversion (YUV→RGB)
- [ ] Blur/pixelation effects
- [ ] ML inference integration
- [ ] Policy engine
- [ ] Unit tests + benchmarks

### Phase 4: Integration (Week 9-10)
- [ ] Connect Android ↔ Rust
- [ ] End-to-end frame processing
- [ ] Memory optimization (pooling)
- [ ] Performance profiling
- [ ] Bug fixes

### Phase 5: Features (Week 11-12)
- [ ] Optional Accessibility Service
- [ ] Optional NotificationListener
- [ ] Settings UI
- [ ] Privacy Dashboard
- [ ] Adaptive FPS

### Phase 6: Evaluation (Week 13-14)
- [ ] Performance benchmarks
- [ ] Accuracy testing
- [ ] Battery impact measurement
- [ ] User study (optional, 20+ participants)
- [ ] Data analysis

### Phase 7: Thesis Writing (Week 15-24)
- [ ] Introduction
- [ ] Background & Related Work
- [ ] System Design
- [ ] Implementation
- [ ] Evaluation
- [ ] Discussion
- [ ] Conclusion
- [ ] Revisions

---

## 📈 Expected Timeline

| Phase | Duration | Deliverable |
|-------|----------|-------------|
| Setup | 2 weeks | Working dev environment |
| Android Core | 3 weeks | Functional capture & overlay |
| Rust Library | 3 weeks | ML inference pipeline |
| Integration | 2 weeks | End-to-end system |
| Features | 2 weeks | Complete prototype |
| Evaluation | 2 weeks | Performance data, user study |
| Writing | 10 weeks | Thesis draft |
| Revision | 2 weeks | Final thesis |
| **Total** | **26 weeks** | **Submitted thesis** |

*Adjust based on program requirements and your pace*

---

## 🎯 Success Criteria

### Technical
- ✅ Latency < 100ms on mid-range devices
- ✅ Accuracy > 90%
- ✅ Battery drain < 15%/hour
- ✅ Memory usage < 150MB
- ✅ No crashes on low-end devices

### Academic
- ✅ Clear research contribution
- ✅ Comprehensive evaluation
- ✅ Ethical analysis
- ✅ Reproducible (open-source)
- ✅ Publication-quality writing

### Privacy
- ✅ No screenshots stored
- ✅ All processing on-device
- ✅ User control and transparency
- ✅ GDPR-compliant (data minimization)

---

## 🔍 Where to Go Next

### Starting Implementation?
→ Go to [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) for setup instructions

### Need Design Clarity?
→ Go to [ARCHITECTURE.md](ARCHITECTURE.md) for system overview

### Ready to Code?
→ Go to [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for step-by-step guide

### Planning Evaluation?
→ Go to [EVALUATION.md](EVALUATION.md) for testing methodology

### Writing Thesis?
→ Go to [THESIS_OUTLINE.md](THESIS_OUTLINE.md) for academic structure

### Privacy Questions?
→ Go to [PRIVACY_SECURITY.md](PRIVACY_SECURITY.md) for guidelines

---

## 📞 Support

**Questions?** Check the relevant document above.

**Issues?** See PROJECT_STRUCTURE.md troubleshooting section.

**Contributions?** See CONTRIBUTING.md (create based on PROJECT_STRUCTURE.md).

---

## ✅ Final Checklist

Before starting implementation, ensure you have:

- [ ] Read README.md for project overview
- [ ] Reviewed ARCHITECTURE.md to understand design
- [ ] Set up development environment (PROJECT_STRUCTURE.md)
- [ ] Understood privacy requirements (PRIVACY_SECURITY.md)
- [ ] Planned evaluation approach (EVALUATION.md)
- [ ] Outlined thesis structure (THESIS_OUTLINE.md)

**You're ready to build Pavlova! 🚀**

---

## 📄 License

All documentation: Creative Commons Attribution 4.0 International (CC BY 4.0)

Suggested code license: MIT (see individual documents for details)

---

**Last Updated**: 2026-02-25  
**Documentation Version**: 1.0  
**Status**: Complete

---

<p align="center">
  <strong>Complete technical foundation for a Master's thesis on privacy-preserving mobile content filtering.</strong>
</p>
